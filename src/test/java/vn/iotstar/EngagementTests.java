package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.repository.*;
import vn.iotstar.service.EngagementService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngagementTests extends CustomerTestFixtures {
    @Autowired EngagementService engagement;
    @Autowired FavoriteRepository favorites;
    @Autowired ProductViewRepository views;

    @Test
    void favoritesAreIsolatedAndCountsPersistAcrossUsers() {
        assertThat(engagement.toggleFavorite(customer, tea.getId())).isTrue();
        assertThat(engagement.toggleFavorite(other, tea.getId())).isTrue();
        assertThat(products.findById(tea.getId()).orElseThrow().getFavoriteCount()).isEqualTo(2);
        assertThat(engagement.favorites(customer, 0).getTotalElements()).isEqualTo(1);
        assertThat(engagement.toggleFavorite(customer, tea.getId())).isFalse();
        assertThat(engagement.favorites(customer, 0)).isEmpty();
        assertThat(engagement.isFavorite(other, tea)).isTrue();
        assertThat(products.findById(tea.getId()).orElseThrow().getFavoriteCount()).isEqualTo(1);
    }

    @Test
    void hiddenProductsDoNotLeakThroughFavoritesOrViewsAndCanBeUnliked() {
        engagement.toggleFavorite(customer, tea.getId()); engagement.recordView(tea, customer);
        tea.setStatus(ProductStatus.HIDDEN); products.saveAndFlush(tea);
        assertThat(engagement.favorites(customer, 0)).isEmpty();
        assertThat(engagement.viewed(customer, 0)).isEmpty();
        assertThat(engagement.toggleFavorite(customer, tea.getId())).isFalse();
        assertThatThrownBy(() -> engagement.toggleFavorite(customer, tea.getId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void onlyLoggedInCustomersRecordViewsAndRepeatedViewsUpdateOneRecord() throws Exception {
        mvc.perform(get("/products/" + tea.getId())).andExpect(status().isOk());
        assertThat(views.count()).isZero();
        mvc.perform(get("/products/" + tea.getId()).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        assertThat(views.count()).isZero();
        mvc.perform(get("/products/" + tea.getId()).with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
        var previous = views.findByUserIdAndProductId(customer.getId(), tea.getId()).orElseThrow().getViewedAt();
        mvc.perform(get("/products/" + tea.getId()).with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
        assertThat(views.count()).isEqualTo(1);
        assertThat(engagement.viewed(other, 0)).isEmpty();
        assertThat(views.findByUserIdAndProductId(customer.getId(), tea.getId()).orElseThrow().getViewedAt())
            .isAfterOrEqualTo(previous);
    }

    @Test
    void favoritesAndHistoryPaginateAtTwentyAndRender() throws Exception {
        for (int i = 0; i < 22; i++) {
            var product = new Product(); product.setShop(tea.getShop()); product.setCategory(tea.getCategory());
            product.setName("Trà " + i); product.setSlug("tra-" + i); product.setSku(UUID.randomUUID().toString());
            product.setDescription("Mô tả"); product.setPrice(new BigDecimal("35000")); product.setStock(10);
            products.saveAndFlush(product); engagement.toggleFavorite(customer, product.getId());
            engagement.recordView(product, customer);
        }
        assertThat(engagement.favorites(customer, 0).getContent()).hasSize(20);
        assertThat(engagement.favorites(customer, 1).getContent()).hasSize(2);
        assertThat(engagement.viewed(customer, 0).getContent()).hasSize(20);
        assertThat(engagement.viewed(customer, 1).getContent()).hasSize(2);
        for (String path : new String[]{"favorites", "viewed"}) {
            mvc.perform(get("/user/" + path).with(user(customer.getUsername()).roles("USER")))
                .andExpect(status().isOk()).andExpect(view().name("user/" + path));
        }
    }

    @Test
    void favoriteEndpointRequiresCsrfAndCannotRedirectOffsite() throws Exception {
        mvc.perform(post("/user/favorites/" + tea.getId()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isForbidden());
        assertThat(favorites.count()).isZero();
        mvc.perform(post("/user/favorites/" + tea.getId()).with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("returnUrl", "//evil.test")) .andExpect(redirectedUrl("/user/favorites"));
        assertThat(favorites.count()).isEqualTo(1);
    }
}
