package vn.iotstar.config;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CartOptionsSchemaMigration implements ApplicationRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String oldConstraint = findOldUniqueConstraint(metadata, connection.getCatalog(), connection.getSchema());
            if (oldConstraint == null) return;
            if (!oldConstraint.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("Tên ràng buộc giỏ hàng không hợp lệ.");
            }
            String database = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String sql = database.contains("mysql") || database.contains("mariadb")
                ? "ALTER TABLE cart_items DROP INDEX " + oldConstraint
                : "ALTER TABLE cart_items DROP CONSTRAINT " + oldConstraint;
            jdbcTemplate.execute(sql);
            log.info("Đã nâng cấp giỏ hàng để hỗ trợ nhiều cấu hình cho cùng một sản phẩm.");
        }
    }

    private String findOldUniqueConstraint(DatabaseMetaData metadata, String catalog, String schema) throws Exception {
        for (String table : new String[]{"cart_items", "CART_ITEMS"}) {
            try (ResultSet indexes = metadata.getIndexInfo(catalog, schema, table, true, false)) {
                java.util.Map<String, Set<String>> columns = new java.util.LinkedHashMap<>();
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    String column = indexes.getString("COLUMN_NAME");
                    if (name != null && column != null) {
                        columns.computeIfAbsent(name, ignored -> new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
                    }
                }
                for (var entry : columns.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase("uk_cart_product")
                        || entry.getValue().equals(Set.of("cart_id", "product_id"))) return entry.getKey();
                }
            }
        }
        return null;
    }
}
