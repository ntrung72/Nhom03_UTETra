package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.SearchHistory;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.SearchHistoryRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
public class SearchSuggestionService {
    private static final int MAX_QUERY_LENGTH = 80;
    private final CatalogService catalogService;
    private final CategoryRepository categoryRepository;
    private final ShopRepository shopRepository;
    private final SearchHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public SearchSuggestions suggest(String rawQuery, User user) {
        String query = clean(rawQuery);
        String matchable = query == null ? null : query.replace('%', ' ').replace('_', ' ')
            .replaceAll("\\s+", " ").trim();
        String matchingQuery = matchable == null || matchable.isBlank() ? null : matchable;
        List<String> history = user == null ? List.of() : historyRepository
            .suggestForUser(user.getId(), matchingQuery == null ? null : normalized(matchingQuery),
                PageRequest.of(0, 5)).stream().map(SearchHistory::getKeyword).toList();
        List<Product> found = catalogService.search(matchingQuery, null, null, "bestselling", 0,
            null, null, null, false, true).getContent().stream().limit(5).toList();
        List<ProductHint> products = found.stream()
            .map(product -> new ProductHint(product.getId(), product.getName(), product.getShop().getName()))
            .toList();
        if (matchingQuery == null) {
            return new SearchSuggestions(products, List.of(), List.of(), history, true);
        }

        String slugQuery = SlugUtils.toSlug(matchingQuery);
        List<ShopHint> shops = shopRepository.suggestPublic(matchingQuery, slugQuery, ShopStatus.APPROVED,
            PageRequest.of(0, 3)).stream().map(shop -> new ShopHint(shop.getSlug(), shop.getName())).toList();
        LinkedHashSet<String> related = new LinkedHashSet<>();
        categoryRepository.suggestPublic(matchingQuery, slugQuery, PageRequest.of(0, 5)).stream()
            .map(category -> category.getName())
            .forEach(related::add);
        found.stream().map(Product::getName).filter(name -> !name.equalsIgnoreCase(matchingQuery))
            .forEach(related::add);
        return new SearchSuggestions(products, shops, new ArrayList<>(related).stream().limit(5).toList(),
            history, false);
    }

    @Transactional
    public void recordSearch(User user, String rawQuery) {
        if (user == null) return;
        String keyword = clean(rawQuery);
        if (keyword == null || keyword.chars().noneMatch(Character::isLetterOrDigit)) return;
        String normalizedKeyword = normalized(keyword);
        SearchHistory history = historyRepository.findByUserIdAndNormalizedKeyword(user.getId(), normalizedKeyword)
            .orElseGet(() -> {
                SearchHistory created = new SearchHistory();
                created.setUser(user);
                created.setNormalizedKeyword(normalizedKeyword);
                return created;
            });
        if (history.getId() != null) history.setSearchCount(history.getSearchCount() + 1);
        history.setKeyword(keyword);
        history.setSearchedAt(VietnamTime.now());
        historyRepository.save(history);
    }

    @Transactional
    public void deleteSearch(User user, String rawKeyword) {
        String keyword = clean(rawKeyword);
        if (keyword == null) return;
        historyRepository.findByUserIdAndNormalizedKeyword(user.getId(), normalized(keyword))
            .ifPresent(historyRepository::delete);
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim().replaceAll("\\s+", " ");
        if (cleaned.length() > MAX_QUERY_LENGTH) cleaned = cleaned.substring(0, MAX_QUERY_LENGTH).trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
            .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    public record ProductHint(Long id, String name, String shopName) {}
    public record ShopHint(String slug, String name) {}
    public record SearchSuggestions(List<ProductHint> products, List<ShopHint> shops,
                                    List<String> keywords, List<String> history, boolean popular) {}
}
