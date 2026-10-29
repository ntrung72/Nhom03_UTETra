package vn.iotstar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import vn.iotstar.entity.ReturnRequestMedia;

public interface ReturnRequestMediaRepository extends JpaRepository<ReturnRequestMedia, Long> {
    @Query("select media.id from ReturnRequestMedia media where media.url not like 'private:%'")
    java.util.List<Long> findLegacyEvidenceIds();
    @Query("select media from ReturnRequestMedia media join fetch media.returnRequest request join fetch request.order where media.id = :id")
    Optional<ReturnRequestMedia> findDetailedById(@Param("id") Long id);
}
