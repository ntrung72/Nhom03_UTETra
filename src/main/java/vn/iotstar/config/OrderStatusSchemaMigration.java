package vn.iotstar.config;

import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate's ddl-auto=update does not widen an existing SQL Server enum check
 * constraint when Java enum values are added. Keep legacy databases compatible
 * with the Vendor -> Shipper workflow without requiring users to recreate data.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class OrderStatusSchemaMigration implements ApplicationRunner {
    private static final String VALUES =
        "'NEW','CONFIRMED','PREPARING','READY_FOR_PICKUP','PICKED_UP','SHIPPING',"
            + "'DELIVERED','DELIVERY_FAILED','CANCELLED','RETURN_REQUESTED','RETURNED_REFUNDED'";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("microsoft sql server")) {
                return;
            }
        }

        ensureConstraint("customer_orders", "status", "ck_customer_orders_status");
        ensureConstraint("order_status_history", "old_status", "ck_order_status_history_old_status");
        ensureConstraint("order_status_history", "new_status", "ck_order_status_history_new_status");
        ensurePaymentStatusConstraint();
        ensurePaymentMethodConstraint();
        ensurePaymentTransactionStatusConstraint();
        ensureReturnStatusConstraint();
    }

    private void ensureConstraint(String table, String column, String constraintName) {
        List<CheckConstraint> matches = constraintsFor(table).stream()
            .filter(constraint -> references(constraint.definition(), column)).toList();

        boolean alreadyCurrent = matches.stream().anyMatch(constraint -> {
            String definition = constraint.definition().toUpperCase(Locale.ROOT);
            return definition.contains("PREPARING") && definition.contains("READY_FOR_PICKUP")
                && definition.contains("DELIVERY_FAILED");
        });
        if (alreadyCurrent) return;

        matches.forEach(constraint -> {
            requireSafeIdentifier(constraint.name());
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint.name());
        });
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName
            + " CHECK (" + column + " IN (" + VALUES + "))");
        log.info("Đã cập nhật ràng buộc trạng thái {}.{} cho luồng Vendor - Shipper.", table, column);
    }

    private void ensurePaymentStatusConstraint() {
        boolean exists = constraintsFor("customer_orders").stream()
            .anyMatch(constraint -> references(constraint.definition(), "payment_status"));
        if (exists) return;
        jdbcTemplate.execute("ALTER TABLE customer_orders ADD CONSTRAINT ck_customer_orders_payment_status "
            + "CHECK (payment_status IN ('UNPAID','PAID','REFUNDED'))");
        log.info("Đã bảo đảm ràng buộc trạng thái thanh toán của đơn hàng.");
    }

    private void ensurePaymentMethodConstraint() {
        List<CheckConstraint> matches = constraintsFor("customer_orders").stream()
            .filter(constraint -> references(constraint.definition(), "payment_method")).toList();
        boolean alreadyCurrent = matches.stream()
            .anyMatch(constraint -> constraint.definition().toUpperCase(Locale.ROOT).contains("VNPAY"));
        if (alreadyCurrent) return;
        matches.forEach(constraint -> {
            requireSafeIdentifier(constraint.name());
            jdbcTemplate.execute("ALTER TABLE customer_orders DROP CONSTRAINT " + constraint.name());
        });
        jdbcTemplate.execute("ALTER TABLE customer_orders ADD CONSTRAINT ck_customer_orders_payment_method "
            + "CHECK (payment_method IN ('COD','VNPAY'))");
        log.info("Đã cập nhật ràng buộc phương thức thanh toán COD/VNPay.");
    }

    private void ensurePaymentTransactionStatusConstraint() {
        List<CheckConstraint> matches = constraintsFor("payment_transactions").stream()
            .filter(constraint -> references(constraint.definition(), "status")).toList();
        boolean alreadyCurrent = matches.stream()
            .anyMatch(constraint -> constraint.definition().toUpperCase(Locale.ROOT).contains("EXPIRED"));
        if (alreadyCurrent) return;
        matches.forEach(constraint -> {
            requireSafeIdentifier(constraint.name());
            jdbcTemplate.execute("ALTER TABLE payment_transactions DROP CONSTRAINT " + constraint.name());
        });
        jdbcTemplate.execute("ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_transactions_status "
            + "CHECK (status IN ('PENDING','SUCCESS','FAILED','CANCELLED','EXPIRED'))");
        log.info("Đã cập nhật ràng buộc trạng thái giao dịch VNPay với trạng thái hết hạn.");
    }

    private void ensureReturnStatusConstraint() {
        List<CheckConstraint> matches = constraintsFor("return_requests").stream()
            .filter(constraint -> references(constraint.definition(), "status")).toList();
        boolean alreadyCurrent = matches.stream()
            .anyMatch(constraint -> constraint.definition().toUpperCase(Locale.ROOT).contains("REFUND_PENDING"));
        if (alreadyCurrent) return;
        matches.forEach(constraint -> {
            requireSafeIdentifier(constraint.name());
            jdbcTemplate.execute("ALTER TABLE return_requests DROP CONSTRAINT " + constraint.name());
        });
        jdbcTemplate.execute("ALTER TABLE return_requests ADD CONSTRAINT ck_return_requests_status "
            + "CHECK (status IN ('REQUESTED','APPROVED','REJECTED','RETURNING','RECEIVED','REFUND_PENDING','REFUNDED'))");
        log.info("Đã cập nhật ràng buộc trạng thái trả hàng với bước chờ hoàn tiền.");
    }

    private List<CheckConstraint> constraintsFor(String table) {
        return jdbcTemplate.query(
            "SELECT name, definition FROM sys.check_constraints WHERE parent_object_id = OBJECT_ID(?)",
            (resultSet, row) -> new CheckConstraint(resultSet.getString("name"), resultSet.getString("definition")),
            table
        );
    }

    private boolean references(String definition, String column) {
        String normalized = definition.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        String identifier = Pattern.quote(column.toLowerCase(Locale.ROOT));
        return Pattern.compile("(?<![a-z0-9_])" + identifier + "(?![a-z0-9_])")
            .matcher(normalized).find();
    }

    private void requireSafeIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Tên ràng buộc trạng thái không hợp lệ.");
        }
    }

    private record CheckConstraint(String name, String definition) {}
}
