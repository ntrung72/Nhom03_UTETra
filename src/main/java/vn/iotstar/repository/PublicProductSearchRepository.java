package vn.iotstar.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.iotstar.entity.Product;

public interface PublicProductSearchRepository {

    Page<Product> searchPublic(
        String q,
        String slugQuery,
        Long categoryId,
        Long shopId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minRating,
        boolean discountedOnly,
        boolean inStockOnly,
        LocalDateTime now,
        String sort,
        Pageable pageable
    );
}