package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.CartItem;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Review;
import vn.iotstar.entity.ReviewMedia;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ReviewRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProductOptionService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SelectedCartCheckoutTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private ProductSizeRepository productSizeRepository;
    @Autowired private ProductOptionService productOptionService;
    @Autowired private ReviewRepository reviewRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletingCartLineRemovesItFromNextRequestAndKeepsOtherLines() throws Exception {
        Fixture fixture = fixture();
        cartService.add(fixture.customer(), fixture.secondProduct().getId(), 1);
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow().getId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var customer = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user(fixture.customer().getUsername()).roles("USER");

        mvc.perform(post("/cart/items/{itemId}/delete", itemId).with(customer).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/cart"));
        mvc.perform(get("/cart").with(customer))
            .andExpect(status().isOk())
            .andExpect(model().attribute("cartCount", 1))
            .andExpect(content().string(not(containsString(fixture.firstProduct().getName()))))
            .andExpect(content().string(containsString(fixture.secondProduct().getName())));
        assertEquals(1, cartService.get(fixture.customer()).getItems().size());
        mvc.perform(get("/api/cart").with(customer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalQuantity").value(2))
            .andExpect(jsonPath("$.items.length()").value(1));

        Long lastItemId = cartService.get(fixture.customer()).getItems().getFirst().getId();
        mvc.perform(post("/cart/items/{itemId}/delete", lastItemId).with(customer).with(csrf()))
            .andExpect(status().is3xxRedirection());
        mvc.perform(get("/cart").with(customer))
            .andExpect(status().isOk())
            .andExpect(model().attribute("cartCount", 0))
            .andExpect(content().string(containsString("Giỏ hàng đang trống")));
        mvc.perform(get("/api/cart").with(customer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalQuantity").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updatingCartLineToZeroRemovesItFromNextRequest() throws Exception {
        Fixture fixture = fixture();
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow().getId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var customer = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user(fixture.customer().getUsername()).roles("USER");

        mvc.perform(post("/cart/items/{itemId}", itemId).param("quantity", "0").with(customer).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/cart"));
        mvc.perform(get("/cart").with(customer))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString(fixture.firstProduct().getName()))))
            .andExpect(content().string(containsString(fixture.secondProduct().getName())));
        assertEquals(1, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void checkoutPreviewNamesTheProductWhenSelectedQuantityExceedsCurrentStock() throws Exception {
        Fixture fixture = fixture();
        Product product = fixture.firstProduct();
        cartService.add(fixture.customer(), product.getId(), 2);
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(product.getId()))
            .findFirst().orElseThrow().getId();
        product.setStock(2);
        productRepository.saveAndFlush(product);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var customer = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .user(fixture.customer().getUsername()).roles("USER");

        mvc.perform(get("/checkout").param("selectionSubmitted", "true")
                .param("itemIds", itemId.toString()).with(customer))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/cart"))
            .andExpect(flash().attribute("error", containsString(product.getName() + " chỉ còn 2 ly")));
        mvc.perform(get("/api/checkout/preview")
                .param("carrierId", fixture.carrier().getId().toString())
                .param("itemIds", itemId.toString()).with(customer))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString(product.getName() + " chỉ còn 2 ly")));
    }

    @Test
    void resultPageShowsOnlyCurrentUsersOrdersAndShoppingLinks() throws Exception {
        Fixture fixture = fixture();
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow().getId();
        var order = orderService.checkout(fixture.customer(), checkoutForm(fixture, List.of(itemId))).getFirst();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/checkout/result").param("orderIds", order.getId().toString())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(order.getCode())))
            .andExpect(content().string(containsString("Xem đơn hàng")))
            .andExpect(content().string(containsString("Tiếp tục mua sắm")));

        User another = user("result-other-" + fixture.suffix(), Role.USER);
        mvc.perform(get("/checkout/result").param("orderIds", order.getId().toString())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(another.getUsername()).roles("USER")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void codCheckoutRedirectsToResultPageForOnlyTheSelectedOrder() throws Exception {
        Fixture fixture = fixture();
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow().getId();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        String resultUrl = mvc.perform(post("/checkout")
                .param("addressId", fixture.address().getId().toString())
                .param("carrierId", fixture.carrier().getId().toString())
                .param("itemIds", itemId.toString())
                .param("paymentMethod", "COD")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertTrue(resultUrl.startsWith("/checkout/result?orderIds="));
        mvc.perform(get(resultUrl)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Xem đơn hàng")));
        assertEquals(1, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void buyNowCreatesOnlyTheChosenLineAndDoesNotMergeAnExistingCartItem() throws Exception {
        Fixture fixture = fixture();
        Long productId = fixture.firstProduct().getId();
        CartItem existing = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(productId)).findFirst().orElseThrow();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/products/{id}", productId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/cart/buy-now/" + productId)))
            .andExpect(content().string(containsString("detailEstimatedTotal")))
            .andExpect(content().string(containsString("detail-availability")));

        String checkoutUrl = mvc.perform(post("/cart/buy-now/{productId}", productId)
                .param("quantity", "2")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertTrue(checkoutUrl.startsWith("/checkout?selectionSubmitted=true&itemIds="));
        Long newItemId = Long.valueOf(checkoutUrl.substring(checkoutUrl.lastIndexOf('=') + 1));
        CartItem newItem = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getId().equals(newItemId)).findFirst().orElseThrow();
        assertNotEquals(existing.getId(), newItem.getId());
        assertEquals(1, existing.getQuantity());
        assertEquals(2, newItem.getQuantity());
        assertEquals(3, cartService.get(fixture.customer()).getItems().size());

        mvc.perform(get(checkoutUrl).with(org.springframework.security.test.web.servlet.request
                .SecurityMockMvcRequestPostProcessors.user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Địa chỉ nhận hàng")))
            .andExpect(content().string(containsString("Vận chuyển")))
            .andExpect(content().string(containsString("Voucher")))
            .andExpect(content().string(containsString("Thanh toán")))
            .andExpect(content().string(containsString("checkoutFormState")))
            .andExpect(content().string(containsString("checkoutSummary")));
    }

    @Test
    void soldOutProductCanBeViewedButCannotBeBoughtDirectly() throws Exception {
        Fixture fixture = fixture();
        Product product = fixture.firstProduct();
        product.setStock(0);
        productRepository.saveAndFlush(product);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/products/{id}", product.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Sản phẩm hiện hết hàng")))
            .andExpect(content().string(not(containsString("Mua ngay"))));
        mvc.perform(post("/cart/buy-now/{productId}", product.getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/products/" + product.getId()));
        assertEquals(2, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void reviewFiltersMatchStarsAndMediaWithoutShowingOtherReviews() throws Exception {
        Fixture fixture = fixture();
        Long productId = fixture.firstProduct().getId();
        Long firstCartItemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(productId)).findFirst().orElseThrow().getId();
        var firstOrder = orderService.checkout(fixture.customer(), checkoutForm(fixture, List.of(firstCartItemId))).getFirst();
        cartService.add(fixture.customer(), productId, 1);
        Long secondCartItemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(productId)).findFirst().orElseThrow().getId();
        var secondOrder = orderService.checkout(fixture.customer(), checkoutForm(fixture, List.of(secondCartItemId))).getFirst();

        Review withImage = review(fixture, firstOrder.getItems().getFirst(), 5, "Đánh giá năm sao có ảnh của sản phẩm.");
        ReviewMedia image = new ReviewMedia();
        image.setReview(withImage);
        image.setType(MediaType.IMAGE);
        image.setUrl("/uploads/reviews/test.png");
        withImage.getMedia().add(image);
        reviewRepository.saveAndFlush(withImage);
        reviewRepository.saveAndFlush(review(fixture, secondOrder.getItems().getFirst(), 2,
            "Đánh giá hai sao không có ảnh của sản phẩm."));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/products/{id}", productId).param("rating", "5").param("mediaOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Đánh giá năm sao có ảnh")))
            .andExpect(content().string(not(containsString("Đánh giá hai sao không có ảnh"))))
            .andExpect(content().string(containsString("data-review-lightbox")));
        mvc.perform(get("/products/{id}", productId).param("mediaOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Đánh giá năm sao có ảnh")))
            .andExpect(content().string(not(containsString("Đánh giá hai sao không có ảnh"))));
        mvc.perform(get("/products/{id}", productId).param("rating", "2").param("mediaOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Không có đánh giá phù hợp")));
        mvc.perform(get("/products/{id}", productId).param("rating", "6"))
            .andExpect(status().isBadRequest());
    }

    private Review review(Fixture fixture, vn.iotstar.entity.OrderItem orderItem, int rating, String content) {
        Review review = new Review();
        review.setUser(fixture.customer());
        review.setProduct(fixture.firstProduct());
        review.setOrderItem(orderItem);
        review.setRating(rating);
        review.setContent(content);
        return review;
    }

    @Test
    void rejectsCombinedQuantityOfDifferentSizesBeforeChangingStock() {
        Fixture fixture = fixture(true);
        List<Long> itemIds = addTwoSizes(fixture, 2, 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> orderService.checkout(fixture.customer(), checkoutForm(fixture, itemIds)));

        assertTrue(error.getMessage().contains("không đủ tồn kho"));
        assertEquals(3, fixture.firstProduct().getStock());
        assertTrue(inventoryTransactionRepository
            .findByProductIdOrderByCreatedAtAscIdAsc(fixture.firstProduct().getId()).isEmpty());
        assertEquals(3, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void acceptsDifferentSizesWhenTheirCombinedQuantityExactlyMatchesStock() {
        Fixture fixture = fixture(true);
        List<Long> itemIds = addTwoSizes(fixture, 1, 2);

        var order = orderService.checkout(fixture.customer(), checkoutForm(fixture, itemIds)).getFirst();

        assertEquals(2, order.getItems().size());
        assertEquals(3, order.getItems().stream().mapToInt(item -> item.getQuantity()).sum());
        assertEquals(0, fixture.firstProduct().getStock());
        var history = inventoryTransactionRepository
            .findByProductIdOrderByCreatedAtAscIdAsc(fixture.firstProduct().getId());
        assertEquals(2, history.size());
        assertEquals(-3, history.stream().mapToInt(item -> item.getQuantityChange()).sum());
        assertEquals(0, history.getLast().getStockAfter());
        assertEquals(1, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void inactiveCategoryBlocksProductDetailDirectCartAddAndExistingCartCheckout() throws Exception {
        Fixture fixture = fixture();
        Long productId = fixture.firstProduct().getId();
        Long itemId = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(productId))
            .findFirst().orElseThrow().getId();
        Category category = fixture.firstProduct().getCategory();
        category.setActive(false);
        categoryRepository.saveAndFlush(category);

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/products/{id}", productId)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/cart/add/{productId}", productId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/checkout").param("itemIds", itemId.toString())
                .param("selectionSubmitted", "true")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/cart"));

        assertThrows(IllegalArgumentException.class,
            () -> orderService.checkout(fixture.customer(), checkoutForm(fixture, List.of(itemId))));
        assertEquals(5, fixture.firstProduct().getStock());
        assertEquals(2, cartService.get(fixture.customer()).getItems().size());
    }

    @Test
    void checksOutOnlySelectedLinesAndKeepsTheRestInCart() {
        Fixture fixture = fixture();
        var cart = cartService.get(fixture.customer());
        CartItem selected = cart.getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow();

        CheckoutForm form = checkoutForm(fixture, List.of(selected.getId()));
        var orders = orderService.checkout(fixture.customer(), form);

        assertEquals(1, orders.size());
        assertEquals(fixture.firstShop().getId(), orders.getFirst().getShop().getId());
        assertEquals(1, orders.getFirst().getItems().size());
        assertEquals(fixture.firstProduct().getId(), orders.getFirst().getItems().getFirst().getProduct().getId());
        assertEquals(4, fixture.firstProduct().getStock());
        assertEquals(5, fixture.secondProduct().getStock());
        var history = inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(fixture.firstProduct().getId());
        assertEquals(1, history.size());
        assertEquals(InventoryTransactionType.SALE, history.getFirst().getType());
        assertEquals(-1, history.getFirst().getQuantityChange());
        assertEquals(5, history.getFirst().getStockBefore());
        assertEquals(4, history.getFirst().getStockAfter());
        assertEquals(orders.getFirst().getId(), history.getFirst().getOrder().getId());

        var remaining = cartService.get(fixture.customer()).getItems();
        assertEquals(1, remaining.size());
        assertEquals(fixture.secondProduct().getId(), remaining.getFirst().getProduct().getId());
    }

    @Test
    void cancellingOrderRestoresStockAndRecordsBothMovements() {
        Fixture fixture = fixture();
        CartItem selected = cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(fixture.firstProduct().getId()))
            .findFirst().orElseThrow();
        var order = orderService.checkout(fixture.customer(), checkoutForm(fixture, List.of(selected.getId()))).getFirst();

        orderService.cancelByUser(fixture.customer(), order.getId());

        assertEquals(5, fixture.firstProduct().getStock());
        var history = inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(fixture.firstProduct().getId());
        assertEquals(2, history.size());
        assertEquals(InventoryTransactionType.CANCELLATION, history.get(1).getType());
        assertEquals(1, history.get(1).getQuantityChange());
        assertEquals(4, history.get(1).getStockBefore());
        assertEquals(5, history.get(1).getStockAfter());
    }

    @Test
    void rejectsCartItemThatDoesNotBelongToCurrentUser() {
        Fixture fixture = fixture();
        User anotherCustomer = user("selected-other-" + fixture.suffix(), Role.USER);
        cartService.add(anotherCustomer, fixture.firstProduct().getId(), 1);
        Long foreignItemId = cartService.get(anotherCustomer).getItems().getFirst().getId();

        CheckoutForm form = checkoutForm(fixture, List.of(foreignItemId));

        assertThrows(IllegalArgumentException.class,
            () -> orderService.checkout(fixture.customer(), form));
        assertEquals(2, cartService.get(fixture.customer()).getItems().size());
    }

    private List<Long> addTwoSizes(Fixture fixture, int mQuantity, int lQuantity) {
        Product product = fixture.firstProduct();
        product.setStock(3);
        var sizes = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId());
        var sizeM = sizes.stream().filter(size -> "M".equals(size.getCode())).findFirst().orElseThrow();
        var sizeL = sizes.stream().filter(size -> "L".equals(size.getCode())).findFirst().orElseThrow();
        cartService.add(fixture.customer(), product.getId(), mQuantity, sizeM.getId(),
            SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of());
        cartService.add(fixture.customer(), product.getId(), lQuantity, sizeL.getId(),
            SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of());
        return cartService.get(fixture.customer()).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(product.getId()))
            .map(CartItem::getId).toList();
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean withOptions) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = user("selected-user-" + suffix, Role.USER);
        User firstVendor = user("selected-vendor-a-" + suffix, Role.VENDOR);
        User secondVendor = user("selected-vendor-b-" + suffix, Role.VENDOR);
        Shop firstShop = shop(firstVendor, "UTETra Thủ Đức " + suffix, "selected-thu-duc-" + suffix);
        Shop secondShop = shop(secondVendor, "UTETra Quận 9 " + suffix, "selected-q9-" + suffix);
        Category category = new Category();
        category.setName("Selected category " + suffix);
        category.setSlug("selected-category-" + suffix);
        category = categoryRepository.save(category);
        Product firstProduct = product(firstShop, category, "Trà sữa Thủ Đức", "SEL-A-" + suffix);
        Product secondProduct = product(secondShop, category, "Trà sữa Quận 9", "SEL-B-" + suffix);
        if (withOptions) productOptionService.configureDefaults(firstProduct);
        else cartService.add(customer, firstProduct.getId(), 1);
        cartService.add(customer, secondProduct.getId(), 1);

        Address address = new Address();
        address.setUser(customer);
        address.setReceiverName(customer.getFullName());
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("TP. Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("01 Võ Văn Ngân");
        address = addressRepository.save(address);

        Carrier carrier = new Carrier();
        carrier.setName("Selected carrier " + suffix);
        carrier.setShippingFee(new BigDecimal("15000"));
        carrier = carrierRepository.save(carrier);
        return new Fixture(suffix, customer, firstShop, firstProduct, secondProduct, address, carrier);
    }

    private CheckoutForm checkoutForm(Fixture fixture, List<Long> itemIds) {
        CheckoutForm form = new CheckoutForm();
        form.setAddressId(fixture.address().getId());
        form.setCarrierId(fixture.carrier().getId());
        form.setItemIds(itemIds);
        return form;
    }

    private User user(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop shop(User owner, String name, String slug) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(name);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private Product product(Shop shop, Category category, String name, String sku) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(name);
        product.setSlug(sku.toLowerCase());
        product.setSku(sku);
        product.setDescription("Sản phẩm kiểm thử checkout lựa chọn.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(5);
        return productRepository.save(product);
    }

    private record Fixture(String suffix, User customer, Shop firstShop, Product firstProduct,
                           Product secondProduct, Address address, Carrier carrier) {}
}
