package vn.iotstar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ReturnRequestMedia;

public interface ReturnRequestMediaRepository
        extends JpaRepository<ReturnRequestMedia, Long> {
    @org.springframework.data.jpa.repository.Query("select m.id from ReturnRequestMedia m where m.url is not null and m.url not like 'private:%'")
    java.util.List<Long> findLegacyEvidenceIds();

    @org.springframework.data.jpa.repository.Query("select m from ReturnRequestMedia m join fetch m.returnRequest r join fetch r.order where m.id = :id")
    java.util.Optional<ReturnRequestMedia> findDetailedById(@org.springframework.data.repository.query.Param("id") Long id);

}