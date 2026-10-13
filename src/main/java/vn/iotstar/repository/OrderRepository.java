package vn.iotstar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.CustomerOrder;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {}
