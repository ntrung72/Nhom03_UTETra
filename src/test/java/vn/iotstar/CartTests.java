package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CartTests extends CustomerTestFixtures {
    @Autowired CartService carts;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItems;
    @Autowired ProductSizeRepository sizes;
    @Autowired ToppingRepository toppings;
    @Autowired ProductToppingRepository links;
    ProductSize medium;
    ProductSize large;
    Topping pearls;
    Topping jelly;

    @BeforeEach
    void options() {
        medium = size("M", "0"); large = size("L", "7000");
        pearls = topping("Trân châu", "3000"); jelly = topping("Thạch", "4000");
    }

    @Test
    void eachUserHasOneIndependentDatabaseCart() {
        assertThat(carts.get(customer).getId()).isEqualTo(carts.get(customer).getId());
        assertThat(carts.get(other).getId()).isNotEqualTo(carts.get(customer).getId());
    }

    @Test
    void canonicalToppingOrderAndDuplicatesMergeOnlyIdenticalOptions() {
        carts.add(customer, tea.getId(), 1, medium.getId(), SugarLevel.PERCENT_70, IceLevel.LESS_ICE,
            List.of(jelly.getId(), pearls.getId(), jelly.getId()));
        carts.add(customer, tea.getId(), 2, medium.getId(), SugarLevel.PERCENT_70, IceLevel.LESS_ICE,
            List.of(pearls.getId(), jelly.getId()));
        var cart = carts.get(customer); assertThat(cart.getItems()).hasSize(1);
        var line = cart.getItems().getFirst();
        assertThat(line.getQuantity()).isEqualTo(3);
        assertThat(line.getSizeId()).isEqualTo(medium.getId());
        assertThat(line.getSugarLevel()).isEqualTo(SugarLevel.PERCENT_70);
        assertThat(line.getIceLevel()).isEqualTo(IceLevel.LESS_ICE);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("42000");
        assertThat(line.getBasePrice()).isEqualByComparingTo("35000");
        assertThat(line.getOptionPrice()).isEqualByComparingTo("7000");
        assertThat(cart.getSubtotal()).isEqualByComparingTo("126000");
    }

    @Test
    void differentSizeSugarIceOrToppingsStayInSeparateLines() {
        carts.add(customer, tea.getId(), 1, medium.getId(), SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of());
        carts.add(customer, tea.getId(), 1, large.getId(), SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of());
        carts.add(customer, tea.getId(), 1, medium.getId(), SugarLevel.PERCENT_50, IceLevel.NORMAL, List.of());
        carts.add(customer, tea.getId(), 1, medium.getId(), SugarLevel.PERCENT_100, IceLevel.NO_ICE, List.of());
        carts.add(customer, tea.getId(), 1, medium.getId(), SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of(pearls.getId()));
        assertThat(carts.get(customer).getItems()).hasSize(5);
        assertThat(carts.get(customer).getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void stockLimitAppliesAcrossAllVariants() {
        carts.add(customer, tea.getId(), 6, medium.getId(), null, null, List.of());
        assertThatThrownBy(() -> carts.add(customer, tea.getId(), 5, large.getId(), null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(carts.get(customer).getTotalQuantity()).isEqualTo(6);
    }

    @Test
    void rejectsInvalidQuantitiesAndIntegerOverflow() {
        for (int quantity : new int[]{0, -1, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> carts.add(customer, tea.getId(), quantity, medium.getId(), null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }
        carts.add(customer, tea.getId(), 10, medium.getId(), null, null, List.of());
        assertThatThrownBy(() -> carts.add(customer, tea.getId(), Integer.MAX_VALUE, medium.getId(), null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(carts.get(customer).getTotalQuantity()).isEqualTo(10);
    }

    @Test
    void rejectsUnavailableOrForeignOptions() {
        large.setActive(false); sizes.saveAndFlush(large);
        for (Long sizeId : List.of(large.getId(), 999999L)) {
            assertThatThrownBy(() -> carts.add(customer, tea.getId(), 1, sizeId, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (List<Long> ids : List.of(List.of(999999L), List.of(-1L), Arrays.asList((Long) null))) {
            assertThatThrownBy(() -> carts.add(customer, tea.getId(), 1, medium.getId(), null, null, ids))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(carts.get(customer).getItems()).isEmpty();
    }

    @Test
    void standaloneToppingsHaveBasePriceAndRejectDrinkOptions() {
        var type = new Category(); type.setName("Topping"); type.setSlug("topping"); categories.save(type);
        tea.setCategory(type); products.saveAndFlush(tea);
        carts.add(customer, tea.getId(), 2, null, null, null, List.of());
        var line = carts.get(customer).getItems().getFirst();
        assertThat(line.getOptionKey()).isEqualTo("BASE"); assertThat(line.getSizeId()).isNull();
        assertThat(line.getUnitPrice()).isEqualByComparingTo("35000");
        assertThatThrownBy(() -> carts.add(customer, tea.getId(), 1, medium.getId(), null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cartViewRefreshesProductAndOptionPricesAndReportsChanges() {
        carts.add(customer, tea.getId(), 2, medium.getId(), null, null, List.of(pearls.getId()));
        var line = carts.get(customer).getItems().getFirst(); var key = line.getOptionKey();
        tea.setPrice(new BigDecimal("40000")); products.saveAndFlush(tea);
        medium.setExtraPrice(new BigDecimal("2000")); sizes.saveAndFlush(medium);
        pearls.setPrice(new BigDecimal("4000")); toppings.saveAndFlush(pearls);
        var view = carts.view(customer);
        assertThat(view.pricesChanged()).isTrue(); assertThat(view.warnings()).isEmpty();
        assertThat(line.getUnitPrice()).isEqualByComparingTo("46000");
        assertThat(line.getOptionPrice()).isEqualByComparingTo("6000");
        assertThat(line.getOptionKey()).isEqualTo(key);
        assertThat(view.cart().getSubtotal()).isEqualByComparingTo("92000");
        assertThat(carts.view(customer).pricesChanged()).isFalse();
    }

    @Test
    void retiredOptionsAreRemovableAndDoNotBreakOtherLines() throws Exception {
        carts.add(customer, tea.getId(), 1, medium.getId(), null, null, List.of(pearls.getId()));
        carts.add(customer, tea.getId(), 1, large.getId(), null, null, List.of());
        var cart = carts.get(customer); var first = cart.getItems().getFirst(); var second = cart.getItems().getLast();
        pearls.setActive(false); toppings.saveAndFlush(pearls);
        tea.setPrice(new BigDecimal("40000")); products.saveAndFlush(tea);
        var view = carts.view(customer);
        assertThat(view.invalidItemIds()).containsExactly(first.getId());
        assertThat(second.getUnitPrice()).isEqualByComparingTo("47000");
        assertThat(carts.prepareSelection(customer, List.of(second.getId())).subtotal()).isEqualByComparingTo("47000");
        mvc.perform(get("/cart").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(view().name("cart/view"));
        carts.remove(customer, first.getId()); assertThat(carts.get(customer).getItems()).hasSize(1);
    }

    @Test
    void stockReductionAndHiddenProductsInvalidateSelectionWithoutDeletingCart() {
        carts.add(customer, tea.getId(), 5, medium.getId(), null, null, List.of());
        var line = carts.get(customer).getItems().getFirst();
        tea.setStock(2); products.saveAndFlush(tea);
        assertThat(carts.view(customer).invalidItemIds()).contains(line.getId());
        assertThatThrownBy(() -> carts.prepareSelection(customer, List.of(line.getId()))).isInstanceOf(IllegalArgumentException.class);
        tea.setStatus(ProductStatus.HIDDEN); products.saveAndFlush(tea);
        assertThat(carts.view(customer).invalidItemIds()).contains(line.getId());
        assertThat(carts.get(customer).getItems()).hasSize(1);
    }

    @Test
    void selectionUsesOnlyChosenOwnedItemsAndFreshPrices() {
        carts.add(customer, tea.getId(), 2, medium.getId(), null, null, List.of());
        carts.add(customer, tea.getId(), 1, large.getId(), null, null, List.of());
        var selected = carts.get(customer).getItems().getLast();
        tea.setPrice(new BigDecimal("40000")); products.saveAndFlush(tea);
        var selection = carts.prepareSelection(customer, List.of(selected.getId(), selected.getId()));
        assertThat(selection.items()).containsExactly(selected); assertThat(selection.subtotal()).isEqualByComparingTo("47000");
        assertThat(selection.cart().getItems()).hasSize(2);
        assertThatThrownBy(() -> carts.prepareSelection(customer, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> carts.prepareSelection(other, List.of(selected.getId())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void itemOwnershipIsCheckedForUpdateDeleteAndSelectionEndpoints() throws Exception {
        carts.add(other, tea.getId(), 1, medium.getId(), null, null, List.of());
        var line = carts.get(other).getItems().getFirst();
        for (String path : List.of("/cart/items/" + line.getId(), "/cart/items/" + line.getId() + "/delete")) {
            mvc.perform(post(path).with(user(customer.getUsername()).roles("USER")).with(csrf()).param("quantity", "0"))
                .andExpect(status().isNotFound());
        }
        mvc.perform(post("/cart/selection").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("itemIds", line.getId().toString())).andExpect(status().isNotFound());
        assertThat(carts.get(other).getItems()).hasSize(1);
    }

    @Test
    void quantityUpdateChecksAggregateStockAndZeroRemovesLine() {
        carts.add(customer, tea.getId(), 2, medium.getId(), null, null, List.of());
        carts.add(customer, tea.getId(), 3, large.getId(), null, null, List.of());
        var first = carts.get(customer).getItems().getFirst();
        carts.update(customer, first.getId(), 7); assertThat(first.getQuantity()).isEqualTo(7);
        assertThatThrownBy(() -> carts.update(customer, first.getId(), 8)).isInstanceOf(IllegalArgumentException.class);
        carts.update(customer, first.getId(), 0); assertThat(carts.get(customer).getItems()).hasSize(1);
    }

    @Test
    void partialSelectionIsSavedInSessionAndOtherItemsRemain() throws Exception {
        carts.add(customer, tea.getId(), 1, medium.getId(), null, null, List.of());
        carts.add(customer, tea.getId(), 1, large.getId(), null, null, List.of());
        var cart = carts.get(customer); var first = cart.getItems().getFirst(); var session = new MockHttpSession();
        mvc.perform(post("/cart/selection").session(session).with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("itemIds", first.getId().toString())).andExpect(redirectedUrl("/cart"));
        assertThat(session.getAttribute("cartSelection")).isEqualTo(List.of(first.getId()));
        mvc.perform(get("/cart").session(session).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedIds", List.of(first.getId())));
        assertThat(carts.get(customer).getItems()).hasSize(2);
    }

    @Test
    void cartEndpointsRequireCustomerRoleCsrfAndValidEnumValues() throws Exception {
        mvc.perform(post("/cart/add/" + tea.getId()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/cart/add/" + tea.getId()).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/cart/add/" + tea.getId()).with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/cart/add/" + tea.getId()).with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("sugarLevel", "invalid")) .andExpect(status().isBadRequest());
        assertThat(cartItems.count()).isZero();
    }

    private ProductSize size(String code, String price) {
        var size = new ProductSize(); size.setProduct(tea); size.setCode(code); size.setExtraPrice(new BigDecimal(price));
        return sizes.saveAndFlush(size);
    }

    private Topping topping(String name, String price) {
        var topping = new Topping(); topping.setName(name); topping.setPrice(new BigDecimal(price)); toppings.saveAndFlush(topping);
        var link = new ProductTopping(); link.setProduct(tea); link.setTopping(topping); links.saveAndFlush(link); return topping;
    }
}
