package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.entity.ReturnRequest.RefundVerification;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;
import vn.iotstar.service.LoyaltyPointService;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ReturnRequestService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:utetra-step42-inventory;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.datasource.hikari.maximum-pool-size=8"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryConcurrencyRegressionTests {

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired ProductSizeRepository sizes;
    @Autowired AddressRepository addresses;
    @Autowired CarrierRepository carriers;
    @Autowired CartRepository cartRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderStatusHistoryRepository orderHistory;
    @Autowired InventoryTransactionRepository inventoryTransactions;
    @Autowired ReturnRequestRepository returnRequests;
    @Autowired ReturnStatusHistoryRepository returnHistory;
    @Autowired LoyaltyPointTransactionRepository loyaltyTransactions;
    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired ReturnRequestService returns;
    @Autowired LoyaltyPointService loyalty;

    @PersistenceContext
    EntityManager entityManager;

    @MockitoBean
    MediaStorageService mediaStorage;

    @BeforeEach
    void mediaFixture() {
        when(mediaStorage.upload(any(MultipartFile.class), eq("returns")))
            .thenReturn("private:returns/concurrent-proof.png");
    }

    // Dữ liệu mẫu phải commit trước khi các luồng mở giao dịch riêng.
    @Test
    void concurrentVariantCheckoutKeepsStockLedgerAndLoserCartConsistent()
            throws Exception {

        var scenario = scenario(3, 2);

        var attempts = race(
            2,
            (index, awaitStart) -> checkoutAttempt(
                scenario,
                scenario.buyers().get(index),
                awaitStart
            )
        );

        assertThat(attempts.stream().filter(Attempt::accepted).count())
            .isEqualTo(1);
        assertThat(attempts.stream()
            .filter(attempt -> !attempt.accepted()).count())
            .isEqualTo(1);

        var winner = attempts.stream()
            .filter(Attempt::accepted)
            .findFirst()
            .orElseThrow();

        for (var attempt : attempts) {
            if (!attempt.accepted()) {
                assertThat(attempt.rejection())
                    .hasMessageContaining("tồn kho");
            }
        }

        inTransaction(() -> {
            var product = products.findById(
                scenario.productId()
            ).orElseThrow();

            assertThat(product.getStock()).isEqualTo(1);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(product.getSoldCount()).isZero();

            var saved = orderRepository.findForShop(
                scenario.shopId(), null, PageRequest.of(0, 20)
            ).getContent();

            assertThat(saved)
                .extracting(CustomerOrder::getId)
                .containsExactly(winner.orderId());

            assertThat(saved.getFirst().getStatus())
                .isEqualTo(OrderStatus.NEW);
            assertThat(saved.getFirst().getItems()).hasSize(2);
            assertThat(saved.getFirst().getItems().stream()
                .mapToInt(OrderItem::getQuantity).sum())
                .isEqualTo(2);

            var sales = movements(
                scenario.productId(),
                InventoryTransactionType.SALE
            );

            assertThat(sales).hasSize(2);

            int stock = scenario.initialStock();

            for (var movement : sales) {
                assertThat(movement.getStockBefore()).isEqualTo(stock);
                assertThat(movement.getQuantityChange()).isEqualTo(-1);

                stock += movement.getQuantityChange();

                assertThat(movement.getStockAfter())
                    .isEqualTo(stock)
                    .isNotNegative();
                assertThat(movement.getOrder().getId())
                    .isEqualTo(winner.orderId());
            }

            assertThat(stock).isEqualTo(1);

            for (int index = 0; index < attempts.size(); index++) {
                var buyer = scenario.buyers().get(index);
                var items = cartRepository.findByUserId(
                    buyer.userId()
                ).orElseThrow().getItems();

                if (attempts.get(index).accepted()) {
                    assertThat(items).isEmpty();
                } else {
                    assertThat(items)
                        .extracting(CartItem::getId)
                        .containsExactlyInAnyOrderElementsOf(
                            buyer.itemIds()
                        );

                    assertThat(items.stream()
                        .mapToInt(CartItem::getQuantity).sum())
                        .isEqualTo(2);
                }
            }

            return null;
        });
    }

    @Test
    void concurrentRefundOfSameRequestRestoresStockAndPointsOnlyOnce()
            throws Exception {

        var scenario = scenario(2, 1);
        var refund = prepareRefund(
            scenario,
            scenario.buyers().getFirst()
        );

        var attempts = race(
            2,
            (index, awaitStart) -> refundAttempt(
                refund,
                "REFUND-ONCE-" + index,
                awaitStart
            )
        );

        assertThat(attempts.stream().filter(Attempt::accepted).count())
            .isEqualTo(1);
        assertThat(attempts.stream()
            .filter(attempt -> !attempt.accepted()).count())
            .isEqualTo(1);

        String reference = attempts.stream()
            .filter(Attempt::accepted)
            .findFirst()
            .orElseThrow()
            .reference();

        inTransaction(() -> {
            assertRefunded(refund, reference);

            var product = products.findById(
                scenario.productId()
            ).orElseThrow();

            assertThat(product.getStock()).isEqualTo(2);
            assertThat(product.getSoldCount()).isZero();
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            assertThat(movements(
                scenario.productId(),
                InventoryTransactionType.RETURN_REFUND
            )).hasSize(1);

            return null;
        });
    }

    @Test
    void simultaneousRefundsForDifferentOrdersDoNotLoseSharedProductStock()
            throws Exception {

        var scenario = scenario(4, 2);

        var first = prepareRefund(
            scenario,
            scenario.buyers().getFirst()
        );
        var second = prepareRefund(
            scenario,
            scenario.buyers().getLast()
        );

        var refunds = List.of(first, second);

        var attempts = race(
            2,
            (index, awaitStart) -> refundAttempt(
                refunds.get(index),
                "REFUND-SHARED-" + index,
                awaitStart
            )
        );

        assertThat(attempts).allMatch(Attempt::accepted);

        inTransaction(() -> {
            assertRefunded(first, "REFUND-SHARED-0");
            assertRefunded(second, "REFUND-SHARED-1");

            var product = products.findById(
                scenario.productId()
            ).orElseThrow();

            assertThat(product.getStock()).isEqualTo(4);
            assertThat(product.getSoldCount()).isZero();
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);

            var restored = movements(
                scenario.productId(),
                InventoryTransactionType.RETURN_REFUND
            );

            assertThat(restored).hasSize(2);
            assertThat(restored)
                .extracting(InventoryTransaction::getStockBefore)
                .containsExactly(0, 2);
            assertThat(restored)
                .extracting(InventoryTransaction::getStockAfter)
                .containsExactly(2, 4);

            return null;
        });
    }

    @Test
    void refundRollbackRestoresAuditInventoryPaymentAndPointsBeforeRetry() {
        var scenario = scenario(2, 1);
        var refund = prepareRefund(
            scenario,
            scenario.buyers().getFirst()
        );

        var before = inTransaction(() -> new AuditSnapshot(
            history(refund.requestId()).stream()
                .map(ReturnStatusHistory::getId)
                .toList(),
            orderHistory.findByOrderIdOrderByChangedAtAscIdAsc(
                refund.orderId()
            ).stream()
                .map(OrderStatusHistory::getId)
                .toList()
        ));

        transaction().execute(status -> {
            var vendor = users.findById(
                scenario.vendorId()
            ).orElseThrow();

            returns.confirmRefund(
                vendor,
                refund.requestId(),
                "ROLLBACK-001",
                "Khoản hoàn tiền nằm trong giao dịch sẽ bị rollback"
            );

            entityManager.flush();

            assertRefunded(refund, "ROLLBACK-001");
            assertThat(products.findById(scenario.productId())
                .orElseThrow().getStock())
                .isEqualTo(2);

            status.setRollbackOnly();

            return null;
        });

        inTransaction(() -> {
            var request = returnRequests.findById(
                refund.requestId()
            ).orElseThrow();
            var order = orderRepository.findById(
                refund.orderId()
            ).orElseThrow();
            var product = products.findById(
                scenario.productId()
            ).orElseThrow();
            var buyer = users.findById(
                refund.buyer().userId()
            ).orElseThrow();

            assertThat(request.getStatus())
                .isEqualTo(ReturnStatus.REFUND_PENDING);
            assertThat(request.getRefundVerification())
                .isEqualTo(RefundVerification.PENDING);
            assertThat(request.getRefundReference()).isNull();
            assertThat(request.getRefundedAt()).isNull();

            assertThat(order.getStatus())
                .isEqualTo(OrderStatus.RETURN_REQUESTED);
            assertThat(order.getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);

            assertThat(product.getStock()).isZero();
            assertThat(product.getSoldCount()).isEqualTo(2);
            assertThat(product.getStatus())
                .isEqualTo(ProductStatus.OUT_OF_STOCK);

            assertThat(history(refund.requestId()))
                .extracting(ReturnStatusHistory::getId)
                .containsExactlyElementsOf(before.returnIds());

            assertThat(orderHistory.findByOrderIdOrderByChangedAtAscIdAsc(
                refund.orderId()
            )).extracting(OrderStatusHistory::getId)
                .containsExactlyElementsOf(before.orderIds());

            assertThat(movements(
                scenario.productId(),
                InventoryTransactionType.RETURN_REFUND
            )).isEmpty();

            assertThat(loyalty.balance(buyer))
                .isEqualTo(refund.earnedPoints());

            assertThat(loyaltyTransactions.existsByOrderIdAndType(
                refund.orderId(),
                LoyaltyPointType.REFUND_REVERSAL
            )).isFalse();

            return null;
        });

        inTransaction(() -> {
            returns.confirmRefund(
                users.findById(scenario.vendorId()).orElseThrow(),
                refund.requestId(),
                "RETRY-001",
                "Hoàn tiền thành công sau khi thử lại"
            );

            return null;
        });

        inTransaction(() -> {
            assertRefunded(refund, "RETRY-001");

            assertThat(products.findById(scenario.productId())
                .orElseThrow().getStock())
                .isEqualTo(2);

            return null;
        });
    }

    private Attempt checkoutAttempt(
            Scenario scenario,
            Buyer buyer,
            Runnable awaitStart) {

        try {
            Long orderId = inTransaction(() -> {
                var customer = users.findById(
                    buyer.userId()
                ).orElseThrow();
                var cached = products.findById(
                    scenario.productId()
                ).orElseThrow();

                assertThat(cached.getStock())
                    .isEqualTo(scenario.initialStock());

                awaitStart.run();

                var created = orders.checkout(
                    customer,
                    checkoutForm(scenario, buyer)
                );

                assertThat(created).hasSize(1);

                return created.getFirst().getId();
            });

            return new Attempt(orderId, null, null);

        } catch (IllegalArgumentException rejection) {
            return new Attempt(null, null, rejection);
        }
    }

    private Attempt refundAttempt(
            RefundCase refund,
            String reference,
            Runnable awaitStart) {

        try {
            inTransaction(() -> {
                var vendor = users.findById(
                    refund.scenario().vendorId()
                ).orElseThrow();
                var cached = returnRequests.findById(
                    refund.requestId()
                ).orElseThrow();

                assertThat(cached.getStatus())
                    .isEqualTo(ReturnStatus.REFUND_PENDING);
                assertThat(products.findById(refund.scenario().productId())
                    .orElseThrow().getStock())
                    .isZero();

                awaitStart.run();

                returns.confirmRefund(
                    vendor,
                    refund.requestId(),
                    reference,
                    "Đã đối soát khoản hoàn tiền trong kiểm thử đồng thời"
                );

                return null;
            });

            return new Attempt(null, reference, null);

        } catch (IllegalArgumentException rejection) {
            return new Attempt(null, reference, rejection);
        }
    }

    private List<Attempt> race(
            int count,
            BiFunction<Integer, Runnable, Attempt> action)
            throws Exception {

        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(count);
        var futures = new ArrayList<Future<Attempt>>();

        try {
            for (int index = 0; index < count; index++) {
                int worker = index;

                futures.add(pool.submit(() -> action.apply(
                    worker,
                    () -> {
                        ready.countDown();
                        await(start);
                    }
                )));
            }

            assertThat(ready.await(15, TimeUnit.SECONDS))
                .as("Các giao dịch đã nạp dữ liệu trước khi bắt đầu tranh khóa")
                .isTrue();

            start.countDown();

            var results = new ArrayList<Attempt>();

            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            return results;

        } finally {
            start.countDown();
            pool.shutdownNow();

            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS))
                .isTrue();
        }
    }

    private void await(CountDownLatch start) {
        try {
            if (!start.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Hết thời gian chờ bắt đầu giao dịch đồng thời."
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                "Luồng kiểm thử bị ngắt.",
                exception
            );
        }
    }

    private RefundCase prepareRefund(Scenario scenario, Buyer buyer) {
        return inTransaction(() -> {
            var customer = users.findById(
                buyer.userId()
            ).orElseThrow();
            var vendor = users.findById(
                scenario.vendorId()
            ).orElseThrow();

            var order = orders.checkout(
                customer,
                checkoutForm(scenario, buyer)
            ).getFirst();

            var product = products.findById(
                scenario.productId()
            ).orElseThrow();

            order.setStatus(OrderStatus.DELIVERED);
            order.setPaymentStatus(PaymentStatus.PAID);
            order.setDeliveredAt(LocalDateTime.now());

            product.setSoldCount(product.getSoldCount() + 2);
            entityManager.flush();

            loyalty.awardForDeliveredOrder(order);

            var form = new ReturnRequestForm();
            form.setReason(ReturnReason.DAMAGED);
            form.setDescription(
                "Sản phẩm bị đổ và hư hỏng khi giao cho khách hàng."
            );
            form.setRefundAmount(order.getTotalAmount());

            var request = returns.create(
                customer,
                order.getId(),
                form,
                new MultipartFile[] {
                    MediaTestFiles.image("evidence", "proof.png")
                }
            );

            returns.decide(
                vendor,
                request.getId(),
                ReturnStatus.APPROVED,
                order.getTotalAmount(),
                "Chấp nhận nhận lại đầy đủ sản phẩm"
            );

            returns.markReturning(customer, order.getId());

            returns.vendorTransition(
                vendor,
                request.getId(),
                ReturnStatus.RECEIVED,
                "Đã nhận đủ các sản phẩm khách gửi trả"
            );

            returns.vendorTransition(
                vendor,
                request.getId(),
                ReturnStatus.REFUND_PENDING,
                "Chuẩn bị đối soát và xác nhận hoàn tiền"
            );

            entityManager.flush();

            long earned = loyaltyTransactions.findByOrderIdAndType(
                order.getId(),
                LoyaltyPointType.EARN
            ).orElseThrow().getPointsChange();

            assertThat(earned).isPositive();
            assertThat(history(request.getId())).hasSize(5);

            return new RefundCase(
                scenario,
                buyer,
                order.getId(),
                request.getId(),
                earned
            );
        });
    }

    private void assertRefunded(RefundCase refund, String reference) {
        var request = returnRequests.findById(
            refund.requestId()
        ).orElseThrow();
        var order = orderRepository.findById(
            refund.orderId()
        ).orElseThrow();
        var buyer = users.findById(
            refund.buyer().userId()
        ).orElseThrow();

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(request.getRefundVerification())
            .isEqualTo(RefundVerification.MANUAL_CONFIRMED);
        assertThat(request.getRefundReference()).isEqualTo(reference);
        assertThat(request.getRefundedAt()).isNotNull();

        assertThat(order.getStatus())
            .isEqualTo(OrderStatus.RETURNED_REFUNDED);
        assertThat(order.getPaymentStatus())
            .isEqualTo(PaymentStatus.REFUNDED);

        assertThat(history(refund.requestId())).hasSize(6);
        assertThat(history(refund.requestId()))
            .filteredOn(entry ->
                entry.getNewStatus() == ReturnStatus.REFUNDED
            )
            .hasSize(1);

        assertThat(orderHistory.findByOrderIdOrderByChangedAtAscIdAsc(
            refund.orderId()
        )).filteredOn(entry ->
            entry.getNewStatus() == OrderStatus.RETURNED_REFUNDED
        ).hasSize(1);

        var restored = movements(
            refund.scenario().productId(),
            InventoryTransactionType.RETURN_REFUND
        ).stream()
            .filter(entry ->
                entry.getOrder().getId().equals(refund.orderId())
            )
            .toList();

        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst().getQuantityChange()).isEqualTo(2);
        assertThat(loyalty.balance(buyer)).isZero();

        assertThat(loyaltyTransactions.findByOrderIdAndType(
            refund.orderId(),
            LoyaltyPointType.REFUND_REVERSAL
        ).orElseThrow().getPointsChange())
            .isEqualTo(-refund.earnedPoints());
    }

    private Scenario scenario(int stock, int buyerCount) {
        return inTransaction(() -> {
            var vendor = account(Role.VENDOR);

            var shop = new Shop();
            shop.setOwner(vendor);
            shop.setName("Shop kiểm thử hồi quy kho");
            shop.setSlug(UUID.randomUUID().toString());
            shop.setStatus(ShopStatus.APPROVED);
            shop.setEnabled(true);
            shop.setCommissionRate(new BigDecimal("10"));
            shops.saveAndFlush(shop);

            var category = new Category();
            category.setName("Trà kiểm thử hồi quy");
            category.setSlug(UUID.randomUUID().toString());
            category.setActive(true);
            categories.saveAndFlush(category);

            var product = new Product();
            product.setShop(shop);
            product.setCategory(category);
            product.setName("Trà kiểm thử đồng thời");
            product.setSlug(UUID.randomUUID().toString());
            product.setSku(UUID.randomUUID().toString());
            product.setDescription(
                "Sản phẩm dùng kiểm tra tồn kho khi có nhiều giao dịch."
            );
            product.setPrice(new BigDecimal("35000"));
            product.setStock(stock);
            product.setStatus(ProductStatus.ACTIVE);
            products.saveAndFlush(product);

            var medium = size(product, "M", "0");
            var large = size(product, "L", "7000");

            var carrier = new Carrier();
            carrier.setName("INV42-" + UUID.randomUUID());
            carrier.setShippingFee(new BigDecimal("20000"));
            carrier.setActive(true);
            carriers.saveAndFlush(carrier);

            var buyers = new ArrayList<Buyer>();

            for (int index = 0; index < buyerCount; index++) {
                var customer = account(Role.USER);

                var address = new Address();
                address.setUser(customer);
                address.setReceiverName("Khách kiểm thử kho");
                address.setPhone("0901234567");
                address.setProvince("TP HCM");
                address.setDistrict("Thủ Đức");
                address.setWard("Linh Trung");
                address.setDetail("1 Võ Văn Ngân");
                address.setDefaultAddress(true);
                addresses.saveAndFlush(address);

                carts.add(
                    customer, product.getId(),
                    1, medium.getId(), null, null, List.of()
                );
                carts.add(
                    customer, product.getId(),
                    1, large.getId(), null, null, List.of()
                );

                var itemIds = cartRepository.findByUserId(
                    customer.getId()
                ).orElseThrow()
                    .getItems().stream()
                    .map(CartItem::getId)
                    .toList();

                buyers.add(new Buyer(
                    customer.getId(),
                    address.getId(),
                    itemIds
                ));
            }

            return new Scenario(
                product.getId(),
                shop.getId(),
                vendor.getId(),
                carrier.getId(),
                stock,
                List.copyOf(buyers)
            );
        });
    }

    private User account(Role role) {
        String key = "inv42-" + UUID.randomUUID();

        var account = new User();
        account.setUsername(key);
        account.setEmail(key + "@test.vn");
        account.setFullName("Người dùng kiểm thử kho");
        account.setPasswordHash("unused-test-password-hash");
        account.setRole(role);
        account.setEnabled(true);
        account.setLocked(false);

        return users.saveAndFlush(account);
    }

    private ProductSize size(
            Product product,
            String code,
            String extra) {

        var size = new ProductSize();
        size.setProduct(product);
        size.setCode(code);
        size.setExtraPrice(new BigDecimal(extra));
        size.setActive(true);

        return sizes.saveAndFlush(size);
    }

    private CheckoutForm checkoutForm(Scenario scenario, Buyer buyer) {
        var form = new CheckoutForm();
        form.setAddressId(buyer.addressId());
        form.setCarrierId(scenario.carrierId());
        form.setItemIds(buyer.itemIds());
        form.setPaymentMethod(PaymentMethod.COD);

        return form;
    }

    private List<ReturnStatusHistory> history(Long requestId) {
        return returnHistory.findByReturnRequestIdOrderByChangedAtAscIdAsc(
            requestId
        );
    }

    private List<InventoryTransaction> movements(
            Long productId,
            InventoryTransactionType type) {

        return inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(productId)
            .stream()
            .filter(entry -> entry.getType() == type)
            .sorted(Comparator.comparing(InventoryTransaction::getId))
            .toList();
    }

    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(
            TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        template.setTimeout(25);

        return template;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction().execute(status -> work.get());
    }

    private record Buyer(
            Long userId,
            Long addressId,
            List<Long> itemIds) {
    }

    private record Scenario(
            Long productId,
            Long shopId,
            Long vendorId,
            Long carrierId,
            int initialStock,
            List<Buyer> buyers) {
    }

    private record RefundCase(
            Scenario scenario,
            Buyer buyer,
            Long orderId,
            Long requestId,
            long earnedPoints) {
    }

    private record AuditSnapshot(
            List<Long> returnIds,
            List<Long> orderIds) {
    }

    private record Attempt(
            Long orderId,
            String reference,
            IllegalArgumentException rejection) {

        boolean accepted() {
            return rejection == null;
        }
    }
}