package vn.iotstar.repository;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ReturnStatusHistory;

public interface ReturnStatusHistoryRepository extends JpaRepository<ReturnStatusHistory, Long> {
    @EntityGraph(attributePaths = "changedBy")
    List<ReturnStatusHistory> findByReturnRequestIdOrderByChangedAtAscIdAsc(Long requestId);
}
