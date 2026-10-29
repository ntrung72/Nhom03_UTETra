package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.OrderStatusHistory;
import vn.iotstar.util.VietnamTime;

class VietnamTimeTests {
    @Test
    void orderAndStatusTimesUseVietnamTimeEvenWhenServerUsesUtc() {
        LocalDateTime earliest = LocalDateTime.ofInstant(Instant.now(), VietnamTime.ZONE);
        CustomerOrder order = new CustomerOrder();
        OrderStatusHistory history = new OrderStatusHistory();
        LocalDateTime latest = LocalDateTime.ofInstant(Instant.now(), VietnamTime.ZONE);

        assertTrue(VietnamTime.ZONE.equals(ZoneId.of("Asia/Ho_Chi_Minh")));
        assertTrue(!order.getCreatedAt().isBefore(earliest) && !order.getCreatedAt().isAfter(latest));
        assertTrue(!history.getChangedAt().isBefore(earliest) && !history.getChangedAt().isAfter(latest));
        assertFalse(order.getCreatedAt().isBefore(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).plusHours(6)));
    }
}
