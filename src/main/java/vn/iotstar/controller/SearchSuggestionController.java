package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.SearchSuggestionService;

@RestController
@RequiredArgsConstructor
public class SearchSuggestionController {
    private final SearchSuggestionService searchSuggestionService;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/search/suggestions")
    ResponseEntity<SearchSuggestionService.SearchSuggestions> suggestions(@RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(searchSuggestionService.suggest(q, currentUserService.optional().orElse(null)));
    }

    @DeleteMapping("/api/search/history")
    ResponseEntity<Void> deleteHistory(@RequestParam String keyword) {
        searchSuggestionService.deleteSearch(currentUserService.require(), keyword);
        return ResponseEntity.noContent().build();
    }
}
