package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.config.VoucherUsageBackfill;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.repository.*;

/** Nghiệm thu tích hợp: demo seed, DB H2 sạch và backfill voucher dữ liệu cũ. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:step35_demo;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.seed-data=true", "app.vnpay.scheduler-enabled=false"
})
class Step35IntegrationTests {
    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private VoucherRepository vouchers;
    @Autowired private VoucherUsageRepository usages;
    @Autowired private CarrierRepository carriers;
    @Autowired private OrderRepository orders;
    @Autowired private VoucherUsageBackfill backfill;

    @Test
    void demoSeedProvidesAllRolesAndCatalog() {
        assertThat(users.count()).isEqualTo(6);
        assertThat(shops.count()).isEqualTo(2);
        assertThat(categories.count()).isEqualTo(4);
        assertThat(products.count()).isEqualTo(30);
        assertThat(vouchers.count()).isEqualTo(2);
        assertThat(carriers.count()).isEqualTo(2);
    }

    @Test
    void repeatingSeedNeverDuplicatesOrOverwritesExistingRecords() throws Exception {
        long usersBefore = users.count();
        long productsBefore = products.count();
        long shopBefore = shops.count();
        context.getBean("seedUTETra", CommandLineRunner.class).run();
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(products.count()).isEqualTo(productsBefore);
        assertThat(shops.count()).isEqualTo(shopBefore);
    }

    @Test
    void legacyVoucherUsageBackfillIsIdempotent() {
        var customer = users.findByUsernameIgnoreCase("user").orElseThrow();
        var shop = shops.findBySlug("utetra-thu-duc").orElseThrow();
        var voucher = vouchers.findByCodeIgnoreCase("WELCOME20").orElseThrow();
        CustomerOrder order = new CustomerOrder();
        order.setCode("STEP35-LEGACY-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        order.setUser(customer);
        order.setShop(shop);
        order.setVoucher(voucher);
        order.setReceiverName("Khach hang thu nghiem");
        order.setReceiverPhone("0912345678");
        order.setShippingAddress("Ho Chi Minh");
        order.setSubtotal(new BigDecimal("80000"));
        order.setTotalAmount(new BigDecimal("80000"));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order = orders.saveAndFlush(order);
        assertThat(usages.existsByOrderId(order.getId())).isFalse();
        backfill.run(null);
        assertThat(usages.existsByOrderId(order.getId())).isTrue();
        long after = usages.count();
        backfill.run(null);
        assertThat(usages.count()).isEqualTo(after);
    }
}
