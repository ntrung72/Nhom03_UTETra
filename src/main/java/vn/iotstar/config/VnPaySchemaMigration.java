package vn.iotstar.config;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Widen only legacy single-column payment-method checks on an existing SQL Server database. */
@Component @RequiredArgsConstructor @Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class VnPaySchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    @Override @Transactional
    public void run(ApplicationArguments args) {
        Boolean sqlServer = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("microsoft sql server"));
        if (!Boolean.TRUE.equals(sqlServer)) return;
        var checks = jdbc.query("""
            select c.name, c.definition from sys.check_constraints c
            where c.parent_object_id = OBJECT_ID('customer_orders')
              and (c.parent_column_id = COLUMNPROPERTY(c.parent_object_id, 'payment_method', 'ColumnId')
                or exists(select 1 from sys.sql_expression_dependencies d where d.referencing_id=c.object_id
                  and d.referenced_id=c.parent_object_id
                  and d.referenced_minor_id=COLUMNPROPERTY(c.parent_object_id, 'payment_method', 'ColumnId')))
              and not exists(select 1 from sys.sql_expression_dependencies d where d.referencing_id=c.object_id
                and d.referenced_id=c.parent_object_id and d.referenced_minor_id>0
                and d.referenced_minor_id<>COLUMNPROPERTY(c.parent_object_id, 'payment_method', 'ColumnId'))
            """, (r, n) -> new Check(r.getString("name"), r.getString("definition")));
        if (checks.isEmpty() || checks.stream().allMatch(c -> c.definition().toUpperCase(java.util.Locale.ROOT).contains("VNPAY"))) return;
        for (var check : checks) jdbc.execute("ALTER TABLE customer_orders DROP CONSTRAINT [" + check.name().replace("]", "]]") + "]");
        jdbc.execute("ALTER TABLE customer_orders WITH CHECK ADD CONSTRAINT ck_customer_orders_payment_method_vnpay CHECK (payment_method IN ('COD','VNPAY'))");
    }
    private record Check(String name, String definition) {}
}
