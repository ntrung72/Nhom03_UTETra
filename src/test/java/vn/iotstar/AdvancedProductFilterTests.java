package vn.iotstar;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.DomainEnums.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvancedProductFilterTests extends CheckoutTestFixtures {
    @Autowired ObjectMapper mapper;

    @Test
    void combinedDiscountFiltersMatchWebAndApiAcrossPageBoundary() throws Exception {
        promotion(PromotionType.PRODUCT_PERCENT, "20");
        tea.setAverageRating(new BigDecimal("4.50")); products.saveAndFlush(tea);
        for (int i = 0; i < 22; i++) {
            var p = new Product(); p.setShop(tea.getShop()); p.setCategory(tea.getCategory());
            p.setName("Trà sữa " + i); p.setSlug("tra-sua-" + i); p.setSku(UUID.randomUUID().toString());
            p.setDescription("Trà tươi"); p.setPrice(new BigDecimal("35000")); p.setStock(i == 0 ? 0 : 5);
            p.setAverageRating(new BigDecimal(i == 1 ? "3" : "4.50")); products.save(p);
        }
        products.flush();
        List<Long> firstIds = null;
        for (int page = 0; page < 2; page++) {
            var web = mvc.perform(get("/products").param("q", "tra sua")
                .param("shop", tea.getShop().getId().toString()).param("minPrice", "30000")
                .param("maxPrice", "40000").param("minRating", "4").param("discounted", "true")
                .param("inStock", "true").param("sort", "price-asc").param("page", "" + page))
                .andExpect(status().isOk()).andReturn();
            var expected = (Page<?>) web.getModelAndView().getModel().get("products");
            var ids = expected.getContent().stream().map(p -> ((Product) p).getId()).toList();
            var api = mvc.perform(get("/api/products").param("q", "tra sua")
                .param("shop", tea.getShop().getId().toString()).param("minPrice", "30000")
                .param("maxPrice", "40000").param("minRating", "4").param("discounted", "true")
                .param("inStock", "true").param("sort", "price-asc").param("page", "" + page))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.content[0].price.salePrice").value(28000))
                .andReturn().getResponse().getContentAsString();
            var actual = mapper.readTree(api).get("content");
            var apiIds = new java.util.ArrayList<Long>(); actual.forEach(p -> apiIds.add(p.get("id").asLong()));
            assertThat(apiIds).containsExactlyElementsOf(ids);
            if (page == 0) { assertThat(ids).hasSize(20); firstIds = ids; }
            else { assertThat(ids).hasSize(1).doesNotContainAnyElementsOf(firstIds); }
        }
    }

    @Test
    void apiFiltersRespectVisibilityAndRejectMalformedNumbers() throws Exception {
        tea.getCategory().setActive(false); categories.saveAndFlush(tea.getCategory());
        mvc.perform(get("/api/products").param("shop", tea.getShop().getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/products").param("minPrice", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("page", "invalid")).andExpect(status().isBadRequest());
    }
}
