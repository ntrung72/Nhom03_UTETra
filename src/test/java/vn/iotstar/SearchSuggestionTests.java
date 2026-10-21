package vn.iotstar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.service.SearchSuggestionService;
import vn.iotstar.repository.SearchHistoryRepository;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest @ActiveProfiles("test") @Transactional
class SearchSuggestionTests extends CustomerTestFixtures {
    @Autowired SearchSuggestionService suggestions;
    @Autowired SearchHistoryRepository histories;

    @Test void vietnameseSearchAndPublicSuggestionsNeverExposePrivateHistory() throws Exception {
        suggestions.recordSearch(customer, "  TRÀ   SỮA  ");
        assertThat(suggestions.suggest("tra sua", customer).history()).containsExactly("TRÀ SỮA");
        assertThat(suggestions.suggest("", other).history()).isEmpty();
        mvc.perform(get("/api/search/suggestions").param("q", "Trà sữa"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.history").isEmpty()).andExpect(jsonPath("$.products[0].id").value(tea.getId()));
    }
    @Test void hiddenAndSuspendedProductsAreNotSuggested() {
        tea.setStatus(ProductStatus.HIDDEN);products.saveAndFlush(tea);
        assertThat(suggestions.suggest("Trà", null).products()).isEmpty();
        tea.setStatus(ProductStatus.ACTIVE);tea.getShop().setEnabled(false);shops.saveAndFlush(tea.getShop());
        assertThat(suggestions.suggest("Trà", null).products()).isEmpty();
    }
    @Test void normalizedRepeatUpdatesOneRecordAndHistoryIsBounded() {
        suggestions.recordSearch(customer,"Trà sữa");suggestions.recordSearch(customer,"TRA SUA");
        assertThat(histories.findByUserIdAndNormalizedKeyword(customer.getId(),"tra sua")).get()
            .extracting(h->h.getSearchCount()).isEqualTo(2);
        for(int i=0;i<25;i++)suggestions.recordSearch(customer,"Món "+i);
        assertThat(histories.findByUserIdOrderBySearchedAtDescIdDesc(customer.getId(),org.springframework.data.domain.Pageable.unpaged())).hasSize(20);
        assertThat(suggestions.suggest("",customer).history()).hasSize(5);
    }
    @Test void deleteOnlyTouchesCurrentUsersHistory() throws Exception {
        suggestions.recordSearch(customer,"Trà");suggestions.recordSearch(other,"Trà");
        mvc.perform(delete("/api/search/history").param("keyword","Trà").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isNoContent());
        assertThat(suggestions.suggest("",customer).history()).isEmpty();
        assertThat(suggestions.suggest("",other).history()).containsExactly("Trà");
        mvc.perform(delete("/api/search/history").param("keyword","Trà").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized());
    }
    @Test void catalogRendersSidebarChipsAndRetainsFiltersInPagination() throws Exception {
        mvc.perform(get("/products").param("q","Trà").param("inStock","true").param("sort","price-asc"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("catalog-layout")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Bỏ lọc")));
    }
}
