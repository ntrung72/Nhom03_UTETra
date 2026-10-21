package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.SearchHistory;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Long> {
    Optional<SearchHistory> findByUserIdAndNormalizedKeyword(Long userId, String normalizedKeyword);
    List<SearchHistory> findByUserIdOrderBySearchedAtDescIdDesc(Long userId, Pageable pageable);

    @Query("""
        select h from SearchHistory h where h.user.id = :userId
          and (:term is null or h.normalizedKeyword like concat('%', :term, '%'))
        order by h.searchedAt desc, h.id desc
        """)
    List<SearchHistory> suggestForUser(@Param("userId") Long userId, @Param("term") String term,
                                       Pageable pageable);
}
