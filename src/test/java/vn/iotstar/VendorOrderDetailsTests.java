package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.OrderItemRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class VendorOrderDetailsTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private OrderRepository orders;
    @Autowired private OrderItemRepository items;
    @Autowired private EntityManager entityManager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void vendorSeesEveryProductAndItsSavedOptions() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User vendor = saveUser("vendor-" + token, Role.VENDOR);
        User customer = saveUser("customer-" + token, Role.USER);
        Shop shop = shop("shop-" + token, vendor);
        CustomerOrder order = order("ORDER-" + token, customer, shop);
        item(order, shop, "Trà sữa khoai môn", 2, token + "-1", true);
        item(order, shop, "Trà vải hoa hồng", 1, token + "-2", false);
        entityManager.flush();
        entityManager.clear();

        String list = mvc.perform(get("/vendor/orders").with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(list.contains("Trà sữa khoai môn ×2"));
        assertTrue(list.contains("Trà vải hoa hồng ×1"));
        assertTrue(list.contains("/vendor/orders/" + order.getId()));

        String detail = mvc.perform(get("/vendor/orders/{id}", order.getId())
                .with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(detail.contains("Trà sữa khoai môn"));
        assertTrue(detail.contains("Trà vải hoa hồng"));
        assertTrue(detail.contains("×2"));
        assertTrue(detail.contains("Size L"));
        assertTrue(detail.contains("70% đường"));
        assertTrue(detail.contains("Ít đá"));
        assertTrue(detail.contains("Pudding trứng"));
    }

    @Test
    void vendorCannotSeeAnotherShopsOrder() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User firstVendor = saveUser("first-" + token, Role.VENDOR);
        User secondVendor = saveUser("second-" + token, Role.VENDOR);
        User customer = saveUser("customer-" + token, Role.USER);
        shop("first-" + token, firstVendor);
        CustomerOrder otherOrder = order("OTHER-" + token, customer, shop("other-" + token, secondVendor));

        String list = mvc.perform(get("/vendor/orders").with(user(firstVendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(otherOrder.getCode()));
        mvc.perform(get("/vendor/orders/{id}", otherOrder.getId())
                .with(user(firstVendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isNotFound());
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return users.save(account);
    }

    private Shop shop(String slug, User owner) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("UTETra " + slug);
        shop.setSlug(slug);
        return shops.save(shop);
    }

    private CustomerOrder order(String code, User customer, Shop shop) {
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setUser(customer);
        order.setShop(shop);
        order.setReceiverName("Khách thử nghiệm");
        order.setReceiverPhone("0900000000");
        order.setShippingAddress("Địa chỉ thử nghiệm");
        order.setSubtotal(new BigDecimal("80000"));
        order.setTotalAmount(new BigDecimal("80000"));
        order.snapshotCommission(new BigDecimal("5"));
        return orders.save(order);
    }

    private void item(CustomerOrder order, Shop shop, String name, int quantity, String sku, boolean withOptions) {
        Category category = new Category();
        category.setName("Trà sữa " + sku);
        category.setSlug("category-" + sku);
        category.setActive(true);
        category = categories.save(category);

        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(name);
        product.setSlug("product-" + sku);
        product.setSku(sku);
        product.setDescription("Sản phẩm kiểm thử");
        product.setPrice(new BigDecimal("20000"));
        product.setStock(10);
        product = products.save(product);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(name);
        item.setUnitPrice(new BigDecimal("20000"));
        item.setQuantity(quantity);
        item.setLineTotal(new BigDecimal("20000").multiply(BigDecimal.valueOf(quantity)));
        if (withOptions) {
            item.setSize("L");
            item.setSugarLevel(SugarLevel.PERCENT_70);
            item.setIceLevel(IceLevel.LESS_ICE);
            item.setToppings("Pudding trứng");
        }
        items.save(item);
    }
}
