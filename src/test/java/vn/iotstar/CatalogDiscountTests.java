package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import vn.iotstar.entity.Product;
import vn.iotstar.repository.*;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductPricingService;
import vn.iotstar.service.ProductPricingService.ProductPrice;

class CatalogDiscountTests {
    @Test
    void filtersUsingPricingBeforePaginationIncludingProductsAfterFirstTwenty() {
        var repository = mock(ProductRepository.class);
        var pricing = mock(ProductPricingService.class);
        var service = new CatalogService(repository, mock(CategoryRepository.class), mock(ShopRepository.class), pricing);
        var candidates = new ArrayList<Product>();
        var prices = new LinkedHashMap<Long, ProductPrice>();
        for (long id = 1; id <= 45; id++) {
            var product = new Product(); product.setId(id); candidates.add(product);
            boolean promoted = id > 20;
            prices.put(id, new ProductPrice(new BigDecimal("40000"),
                new BigDecimal(promoted ? "30000" : "40000"), BigDecimal.ZERO, "25%", promoted));
        }
        doReturn(new PageImpl<>(candidates)).when(repository).searchPublic(any(), any(), any(), any(),
            any(), any(), any(), anyBoolean(), any(), any(), any());
        when(pricing.prices(candidates)).thenReturn(prices);
        var first = service.search(null, null, null, "bestselling", 0, null, null, null, true, false);
        var second = service.search(null, null, null, "bestselling", 1, null, null, null, true, false);
        assertThat(first.getTotalElements()).isEqualTo(25);
        assertThat(first.getContent()).extracting(Product::getId).containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(21, 40).boxed().toList());
        assertThat(second.getContent()).extracting(Product::getId).containsExactly(41L, 42L, 43L, 44L, 45L);
        assertThat(service.search(null, null, null, null, Integer.MAX_VALUE,
            null, null, null, true, false)).isEmpty();
    }
}
