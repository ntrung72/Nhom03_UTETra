package vn.iotstar.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class VietnamTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private VietnamTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
