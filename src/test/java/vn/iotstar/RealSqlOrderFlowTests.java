package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.iotstar.config.CartOptionsSchemaMigration;
import vn.iotstar.config.CommissionSnapshotBackfill;
import vn.iotstar.config.DemoToppingCatalogBackfill;
import vn.iotstar.config.OrderStatusSchemaMigration;
import vn.iotstar.config.VoucherUsageBackfill;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ShipperAssignmentStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.OrderStatusHistoryRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.PrivateMediaMigrationService;

@EnabledIfSystemProperty(named = "utetra.realSqlTests", matches = "true")
@SpringBootTest(properties = {
    "app.seed-data=false",
    "app.shipper.assignment-check-ms=3600000",
    "spring.jpa.hibernate.ddl-auto=validate",
    "logging.level.org.hibernate.SQL=OFF"
})
@Transactional
class RealSqlOrderFlowTests {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private OrderRepository orders;
    @Autowired private NotificationRepository notifications;
    @Autowired private OrderStatusHistoryRepository histories;
    @Autowired private OrderService orderService;

    @MockitoBean private CartOptionsSchemaMigration cartOptionsSchemaMigration;
    @MockitoBean private OrderStatusSchemaMigration orderStatusSchemaMigration;
    @MockitoBean private CommissionSnapshotBackfill commissionSnapshotBackfill;
    @MockitoBean private VoucherUsageBackfill voucherUsageBackfill;
    @MockitoBean private DemoToppingCatalogBackfill demoToppingCatalogBackfill;
    @MockitoBean private PrivateMediaMigrationService privateMediaMigrationService;
    @MockitoBean private MediaStorageService mediaStorageService;

    @BeforeEach
    void requireRealSqlServer() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductName().contains("Microsoft SQL Server"));
            assertEquals("UTETraDB", connection.getCatalog());
        }
    }

    @Test
    void shipperRejectsThenAcceptsReassignedOrder() {
        User admin = account(Role.ADMIN);
        User shipper = account(Role.SHIPPER);
        CustomerOrder order = order(OrderStatus.READY_FOR_PICKUP, null);
        long adminNotifications = notifications.countByUserIdAndReadFalse(admin.getId());

        orderService.assignShipper(admin, order.getId(), shipper.getId());
        assertEquals(ShipperAssignmentStatus.PENDING, order.getShipperAssignmentStatus());
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP));

        orderService.respondToShipperAssignment(shipper, order.getId(), false, "Không nhận được chuyến này");
        assertNull(order.getShipper());
        assertEquals(ShipperAssignmentStatus.REJECTED, order.getShipperAssignmentStatus());
        assertEquals(adminNotifications + 1, notifications.countByUserIdAndReadFalse(admin.getId()));

        orderService.assignShipper(admin, order.getId(), shipper.getId());
        orderService.respondToShipperAssignment(shipper, order.getId(), true, null);
        assertEquals(ShipperAssignmentStatus.ACCEPTED, order.getShipperAssignmentStatus());
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP);
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
    }

    @Test
    void reassigningAndCancellingNotifiesAffectedShippers() {
        User admin = account(Role.ADMIN);
        User firstShipper = account(Role.SHIPPER);
        User secondShipper = temporaryShipper();
        CustomerOrder order = order(OrderStatus.CONFIRMED, null);
        long firstNotifications = notifications.countByUserIdAndReadFalse(firstShipper.getId());

        orderService.assignShipper(admin, order.getId(), firstShipper.getId());
        orderService.assignShipper(admin, order.getId(), secondShipper.getId());
        assertEquals(firstNotifications + 2, notifications.countByUserIdAndReadFalse(firstShipper.getId()));
        assertEquals(ShipperAssignmentStatus.PENDING, order.getShipperAssignmentStatus());

        orderService.vendorUpdate(order.getShop().getOwner(), order.getId(), OrderStatus.CANCELLED);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(ShipperAssignmentStatus.CANCELLED, order.getShipperAssignmentStatus());
        assertEquals(2, notifications.countByUserIdAndReadFalse(secondShipper.getId()));
    }

    @Test
    void failedDeliveryCanBeAssignedBackToSameShipper() {
        User admin = account(Role.ADMIN);
        User shipper = account(Role.SHIPPER);
        CustomerOrder order = order(OrderStatus.DELIVERY_FAILED, shipper);
        order.setFailureReason("Khách chưa thể nhận hàng");
        order.setDeliveryProofUrl("private:delivery-proofs/rollback-test.jpg");

        orderService.retryFailedDelivery(admin, order.getId(), shipper.getId());
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
        assertEquals(ShipperAssignmentStatus.PENDING, order.getShipperAssignmentStatus());
        assertEquals(shipper.getId(), order.getShipper().getId());
        assertNull(order.getFailureReason());
        assertNull(order.getDeliveryProofUrl());
        assertTrue(histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()).stream()
            .anyMatch(history -> history.getNewStatus() == OrderStatus.DELIVERY_FAILED
                && "private:delivery-proofs/rollback-test.jpg".equals(history.getDeliveryProofUrl())));

        orderService.respondToShipperAssignment(shipper, order.getId(), true, null);
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.SHIPPING);
        assertEquals(OrderStatus.SHIPPING, order.getStatus());
    }

    @Test
    void pendingAssignmentExpiresAndReturnsOrderToDispatch() {
        User admin = account(Role.ADMIN);
        User shipper = account(Role.SHIPPER);
        CustomerOrder order = order(OrderStatus.READY_FOR_PICKUP, null);
        orderService.assignShipper(admin, order.getId(), shipper.getId());
        order.setShipperAssignedAt(java.time.LocalDateTime.now().minusMinutes(31));

        orderService.checkPendingShipperAssignments();

        assertNull(order.getShipper());
        assertEquals(ShipperAssignmentStatus.EXPIRED, order.getShipperAssignmentStatus());
        assertEquals(OrderStatus.READY_FOR_PICKUP, order.getStatus());
    }

    @Test
    void codDeliveryNeedsExplicitCashCollection() {
        User shipper = account(Role.SHIPPER);
        CustomerOrder order = order(OrderStatus.SHIPPING, shipper);
        MockMultipartFile photo = new MockMultipartFile("deliveryProof", "cod.png", "image/png", new byte[] {1, 2, 3});
        when(mediaStorageService.upload(any(), eq("delivery-proofs")))
            .thenReturn("private:delivery-proofs/rollback-cod.jpg");

        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null, photo));
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());

        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null, photo, true);
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertTrue(order.getCodCollectedAt() != null);
    }

    @Test
    void oldCancelledOrderCannotKeepPendingAssignmentAfterMigration() {
        CustomerOrder order = order(OrderStatus.CANCELLED, account(Role.SHIPPER));
        order.setShipperAssignmentStatus(ShipperAssignmentStatus.PENDING);
        entityManager.flush();

        OrderStatusSchemaMigration migration = new OrderStatusSchemaMigration(dataSource, jdbcTemplate);
        ReflectionTestUtils.invokeMethod(migration, "repairCancelledShipperAssignments");
        entityManager.refresh(order);

        assertEquals(ShipperAssignmentStatus.CANCELLED, order.getShipperAssignmentStatus());
    }

    private User account(Role role) {
        return users.findByRoleOrderByFullNameAsc(role).stream().findFirst().orElseThrow();
    }

    private User temporaryShipper() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User shipper = new User();
        shipper.setUsername("rollback-shipper-" + suffix);
        shipper.setEmail("rollback-shipper-" + suffix + "@utetra.test");
        shipper.setPasswordHash("rollback-only");
        shipper.setFullName("Shipper kiểm thử rollback");
        shipper.setRole(Role.SHIPPER);
        shipper.setEnabled(true);
        return users.save(shipper);
    }

    private CustomerOrder order(OrderStatus status, User shipper) {
        Shop shop = shops.findAll().stream().filter(Shop::isApproved).findFirst().orElseThrow();
        CustomerOrder order = new CustomerOrder();
        order.setCode("DBTEST" + UUID.randomUUID().toString().substring(0, 12).replace("-", "").toUpperCase(Locale.ROOT));
        order.setUser(account(Role.USER));
        order.setShop(shop);
        order.setShipper(shipper);
        order.setStatus(status);
        order.setReceiverName("Kiểm thử rollback");
        order.setReceiverPhone("0000000000");
        order.setShippingAddress("Kiểm thử rollback");
        order.setSubtotal(BigDecimal.ZERO);
        order.setTotalAmount(BigDecimal.ZERO);
        return orders.save(order);
    }
}
