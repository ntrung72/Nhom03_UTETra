package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.Product;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvancedProductFilterTests extends CheckoutTestFixtures {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void combinedDiscountFiltersMatchWebAndApiAcrossPageBoundary() throws Exception {
        promotion(PromotionType.PRODUCT_PERCENT, "20");

        tea.setAverageRating(new BigDecimal("4.50"));
        products.saveAndFlush(tea);

        for (int i = 0; i < 22; i++) {
            Product product = new Product();
            product.setShop(tea.getShop());
            product.setCategory(tea.getCategory());
            product.setName("Trà sữa " + i);
            product.setSlug("tra-sua-" + i);
            product.setSku(UUID.randomUUID().toString());
            product.setDescription("Trà tươi");
            product.setPrice(new BigDecimal("35000"));
            product.setStock(i == 0 ? 0 : 5);
            product.setAverageRating(
                new BigDecimal(i == 1 ? "3" : "4.50")
            );
            products.save(product);
        }

        products.flush();

        List<Long> firstIds = List.of();

        for (int page = 0; page < 2; page++) {
            var web = mvc.perform(filteredRequest(
                "/products", "25000", "30000", page
            ))
                .andExpect(status().isOk())
                .andReturn();

            assertThat(web.getModelAndView()).isNotNull();

            Page<?> expected = (Page<?>) web.getModelAndView()
                .getModel().get("products");

            assertThat(expected.getTotalElements()).isEqualTo(21);

            List<Long> ids = expected.getContent().stream()
                .map(value -> ((Product) value).getId())
                .toList();

            String api = mvc.perform(filteredRequest(
                "/api/products", "25000", "30000", page
            ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(
                    jsonPath("$.content[0].price.salePrice").value(28000)
                )
                .andReturn().getResponse().getContentAsString();

            var content = mapper.readTree(api).get("content");

            List<Long> apiIds = new ArrayList<>();
            content.forEach(value ->
                apiIds.add(value.get("id").asLong())
            );

            assertThat(apiIds).containsExactlyElementsOf(ids);

            if (page == 0) {
                assertThat(ids).hasSize(20);
                firstIds = ids;
            } else {
                assertThat(ids).hasSize(1)
                    .doesNotContainAnyElementsOf(firstIds);
            }
        }

        var outsideRange = mvc.perform(filteredRequest(
            "/products", "30000", "40000", 0
        ))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(outsideRange.getModelAndView()).isNotNull();

        Page<?> empty = (Page<?>) outsideRange.getModelAndView()
            .getModel().get("products");

        assertThat(empty.getContent()).isEmpty();
        assertThat(empty.getTotalElements()).isZero();

        mvc.perform(filteredRequest(
            "/api/products", "30000", "40000", 0
        ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void apiFiltersRespectVisibilityAndRejectMalformedNumbers() throws Exception {
        tea.getCategory().setActive(false);
        categories.saveAndFlush(tea.getCategory());

        mvc.perform(get("/api/products")
                .param("shop", tea.getShop().getId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(get("/api/products")
                .param("minPrice", "invalid"))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/products")
                .param("page", "invalid"))
            .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder filteredRequest(
            String endpoint,
            String minPrice,
            String maxPrice,
            int page) {

        return get(endpoint)
            .param("q", "tra sua")
            .param("shop", tea.getShop().getId().toString())
            .param("minPrice", minPrice)
            .param("maxPrice", maxPrice)
            .param("minRating", "4")
            .param("discounted", "true")
            .param("inStock", "true")
            .param("sort", "price-asc")
            .param("page", Integer.toString(page));
    }
}