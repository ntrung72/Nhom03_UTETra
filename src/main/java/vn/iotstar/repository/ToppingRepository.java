package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Topping;

public interface ToppingRepository
        extends JpaRepository<Topping, Long> {

    List<Topping> findByActiveTrueOrderByNameAsc();

    Optional<Topping> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(
        String name,
        Long id
    );

    @Query("""
        select t from Topping t
        where :q is null
           or lower(t.name) like lower(concat('%', :q, '%'))
        """)
    Page<Topping> search(
        @Param("q") String q,
        Pageable pageable
    );
}