package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.*;

class CartPriceDelegationTests {
    @Test
    void snapshotsAndRepricingUseSharedSalePriceAndUnitPricing() {
        var cartRepository = mock(CartRepository.class); var items = mock(CartItemRepository.class);
        var products = mock(ProductRepository.class); var users = mock(UserRepository.class);
        var options = mock(ProductOptionService.class); var pricing = mock(ProductPricingService.class);
        var service = new CartService(cartRepository, items, products, users, options, pricing);
        var user = new User(); user.setId(1L);
        var shop = new Shop(); shop.setStatus(ShopStatus.APPROVED);
        var category = new Category(); category.setSlug("tra-sua");
        var product = new Product(); product.setId(2L); product.setShop(shop); product.setCategory(category);
        product.setName("Trà"); product.setPrice(new BigDecimal("35000")); product.setStock(10);
        var size = new ProductSize(); size.setId(3L); size.setProduct(product); size.setCode("L"); size.setExtraPrice(new BigDecimal("7000"));
        var cart = new Cart(); cart.setId(4L); cart.setUser(user);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUserId(1L)).thenReturn(Optional.of(cart));
        when(products.findByIdForUpdate(2L)).thenReturn(Optional.of(product));
        when(options.availableSizes(product)).thenReturn(List.of(size));
        when(options.availableToppings(product)).thenReturn(List.of());
        when(pricing.price(product)).thenReturn(new ProductPricingService.ProductPrice(
            new BigDecimal("35000"), new BigDecimal("28000"), new BigDecimal("20"), "20%", true));
        when(pricing.unitPrice(product, 3L, List.of())).thenReturn(new BigDecimal("35000"));
        when(items.saveAndFlush(any(CartItem.class))).thenAnswer(invocation -> {
            CartItem line = invocation.getArgument(0); line.setId(5L); return line;
        });
        service.add(user, 2L, 2, 3L, SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of());
        var line = cart.getItems().getFirst();
        assertThat(line.getBasePrice()).isEqualByComparingTo("28000");
        assertThat(line.getOptionPrice()).isEqualByComparingTo("7000");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("35000");
        when(pricing.price(product)).thenReturn(new ProductPricingService.ProductPrice(
            new BigDecimal("35000"), new BigDecimal("25000"), BigDecimal.ZERO, "", true));
        when(pricing.unitPrice(product, 3L, List.of())).thenReturn(new BigDecimal("32000"));
        assertThat(service.view(user).pricesChanged()).isTrue();
        assertThat(line.getUnitPrice()).isEqualByComparingTo("32000");
        verify(pricing, times(2)).unitPrice(product, 3L, List.of());
    }
}
