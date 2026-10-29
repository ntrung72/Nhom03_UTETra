package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    @Query("""
        select c from Category c where c.active = true
          and (lower(c.name) like lower(concat('%', :q, '%'))
            or lower(c.slug) like lower(concat('%', :slugQuery, '%')))
        order by c.name
        """)
    List<Category> suggestPublic(@Param("q") String q, @Param("slugQuery") String slugQuery,
                                 Pageable pageable);
    List<Category> findByActiveTrueOrderByNameAsc();
    Optional<Category> findBySlugAndActiveTrue(String slug);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);

    @Query("select c from Category c where :q is null or lower(c.name) like lower(concat('%', :q, '%'))")
    Page<Category> search(@Param("q") String q, Pageable pageable);
}
