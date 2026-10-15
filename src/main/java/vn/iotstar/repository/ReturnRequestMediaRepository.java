package vn.iotstar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ReturnRequestMedia;

public interface ReturnRequestMediaRepository
        extends JpaRepository<ReturnRequestMedia, Long> {
}