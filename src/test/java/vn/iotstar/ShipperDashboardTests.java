package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShipperAssignmentStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.OrderService;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipperDashboardTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private OrderRepository orders;
    @Autowired private ManagerShopRepository managerShops;
    @Autowired private NotificationRepository notifications;
    @Autowired private OrderService orderService;

    @Test
    void onlyOwningVendorAdvancesPreparationAndShipperWaitsUntilReady() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("workflow-admin-" + suffix, Role.ADMIN);
        User manager = saveUser("workflow-manager-" + suffix, Role.MANAGER);
        User vendor = saveUser("workflow-vendor-" + suffix, Role.VENDOR);
        User otherVendor = saveUser("workflow-other-vendor-" + suffix, Role.VENDOR);
        User customer = saveUser("workflow-buyer-" + suffix, Role.USER);
        User shipper = saveUser("workflow-shipper-" + suffix, Role.SHIPPER);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Workflow");
        shop.setSlug("workflow-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        managerShops.save(assignment);
        CustomerOrder order = saveOrder("WORKFLOW-" + suffix, null, customer, shop, OrderStatus.NEW);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        for (OrderStatus next : List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP)) {
            for (User actor : List.of(admin, manager)) {
                assertThrows(IllegalArgumentException.class,
                    () -> orderService.managementUpdate(actor, order.getId(), next));
                assertThrows(IllegalArgumentException.class,
                    () -> orderService.vendorUpdate(actor, order.getId(), next));
                @SuppressWarnings("unchecked")
                Map<Long, List<OrderStatus>> visibleActions = (Map<Long, List<OrderStatus>>) mvc
                    .perform(get("/management/orders").param("q", order.getCode())
                        .with(user(actor.getUsername()).roles(actor.getRole().name())))
                    .andExpect(status().isOk()).andReturn().getModelAndView().getModel()
                    .get("allowedTransitions");
                assertTrue(!visibleActions.get(order.getId()).contains(next));
                mvc.perform(post("/management/orders/{id}/status", order.getId())
                        .param("status", next.name()).with(csrf())
                        .with(user(actor.getUsername()).roles(actor.getRole().name())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/management/orders"))
                    .andExpect(flash().attributeExists("error"));
                assertEquals(order.getStatus(), orders.findById(order.getId()).orElseThrow().getStatus());
            }
            assertThrows(IllegalArgumentException.class,
                () -> orderService.vendorUpdate(otherVendor, order.getId(), next));
            @SuppressWarnings("unchecked")
            Map<Long, List<OrderStatus>> vendorActions = (Map<Long, List<OrderStatus>>) mvc
                .perform(get("/vendor/orders").param("q", order.getCode())
                    .with(user(vendor.getUsername()).roles("VENDOR")))
                .andExpect(status().isOk()).andReturn().getModelAndView().getModel()
                .get("allowedTransitions");
            assertTrue(vendorActions.get(order.getId()).contains(next));
            orderService.vendorUpdate(vendor, order.getId(), next);
            assertEquals(next, order.getStatus());
            if (next == OrderStatus.CONFIRMED) {
                orderService.assignShipper(admin, order.getId(), shipper.getId());
                orderService.respondToShipperAssignment(shipper, order.getId(), true, null);
            }
            if (next != OrderStatus.READY_FOR_PICKUP) {
                assertThrows(IllegalArgumentException.class,
                    () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP));
            }
        }
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP);
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
    }

    @Test
    void codDeliveryRequiresSeparateCollectionConfirmation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User shipper = saveUser("cod-shipper-" + suffix, Role.SHIPPER);
        User customer = saveUser("cod-buyer-" + suffix, Role.USER);
        User vendor = saveUser("cod-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra COD");
        shop.setSlug("cod-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("COD-" + suffix, shipper, customer, shop, OrderStatus.SHIPPING);
        MockMultipartFile photo = new MockMultipartFile("deliveryProof", "cod.png", "image/png", new byte[] {1, 2, 3});
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String shippingPage = mvc.perform(get("/shipper").param("q", order.getCode())
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(shippingPage.contains("name=\"codCollected\""));
        assertTrue(shippingPage.contains("Đã thu đủ tiền COD từ khách"));

        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null, photo));
        assertEquals(OrderStatus.SHIPPING, order.getStatus());
        assertEquals(PaymentStatus.UNPAID, order.getPaymentStatus());

        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null, photo, true);
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertTrue(order.getCodCollectedAt() != null);
    }

    @Test
    void previouslyAssignedReadyOrderAlsoRequiresShipperAcceptance() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("legacy-admin-" + suffix, Role.ADMIN);
        User shipper = saveUser("legacy-shipper-" + suffix, Role.SHIPPER);
        User customer = saveUser("legacy-buyer-" + suffix, Role.USER);
        User vendor = saveUser("legacy-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Legacy");
        shop.setSlug("legacy-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("LEGACY-" + suffix, shipper, customer, shop, OrderStatus.READY_FOR_PICKUP);
        assertEquals(null, order.getShipperAssignmentStatus());

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String shipperPage = mvc.perform(get("/shipper").param("q", order.getCode())
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(shipperPage.contains("Chấp nhận đơn") && shipperPage.contains("Từ chối đơn"));
        assertTrue(!shipperPage.contains("name=\"status\" value=\"PICKED_UP\""));
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP));

        String managementPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managementPage.contains("Chờ shipper chấp nhận"));

        mvc.perform(post("/shipper/orders/{id}/assignment", order.getId())
                .param("decision", "accept")
                .with(csrf()).with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/shipper"));
        assertEquals(ShipperAssignmentStatus.ACCEPTED, order.getShipperAssignmentStatus());
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP);
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
    }

    @Test
    void shipperCanRejectWithReasonAndReplacementMustAcceptBeforePickup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("response-admin-" + suffix, Role.ADMIN);
        User manager = saveUser("response-manager-" + suffix, Role.MANAGER);
        User first = saveUser("response-first-" + suffix, Role.SHIPPER);
        User replacement = saveUser("response-next-" + suffix, Role.SHIPPER);
        User customer = saveUser("response-buyer-" + suffix, Role.USER);
        User vendor = saveUser("response-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Response");
        shop.setSlug("response-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        managerShops.save(assignment);
        CustomerOrder order = saveOrder("RESPONSE-" + suffix, null, customer, shop, OrderStatus.READY_FOR_PICKUP);
        orderService.assignShipper(admin, order.getId(), first.getId());

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String pendingPage = mvc.perform(get("/shipper").param("q", order.getCode())
                .with(user(first.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(pendingPage.contains("Chấp nhận đơn") && pendingPage.contains("Từ chối đơn"));
        assertTrue(!pendingPage.contains("name=\"status\" value=\"PICKED_UP\""));
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(first, order.getId(), OrderStatus.PICKED_UP));

        mvc.perform(post("/shipper/orders/{id}/assignment", order.getId())
                .param("decision", "reject").param("rejectionReason", "Bận")
                .with(csrf()).with(user(first.getUsername()).roles("SHIPPER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/shipper"));
        assertEquals(ShipperAssignmentStatus.PENDING, order.getShipperAssignmentStatus());

        mvc.perform(post("/shipper/orders/{id}/assignment", order.getId())
                .param("decision", "reject").param("rejectionReason", "Không thể nhận chuyến này")
                .with(csrf()).with(user(first.getUsername()).roles("SHIPPER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/shipper"));
        assertEquals(null, order.getShipper());
        assertEquals(ShipperAssignmentStatus.REJECTED, order.getShipperAssignmentStatus());
        assertEquals("Không thể nhận chuyến này", order.getShipperRejectionReason());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(admin.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Shipper từ chối đơn giao")
                && n.getMessage().contains(order.getCode())));
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(manager.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Shipper từ chối đơn giao")
                && n.getMessage().contains(order.getCode())));

        String managementPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managementPage.contains("Shipper đã từ chối: Không thể nhận chuyến này"));

        orderService.assignShipper(admin, order.getId(), replacement.getId());
        assertEquals(ShipperAssignmentStatus.PENDING, order.getShipperAssignmentStatus());
        assertEquals(null, order.getShipperRejectionReason());
        assertThrows(IllegalArgumentException.class,
            () -> orderService.respondToShipperAssignment(first, order.getId(), true, null));
        mvc.perform(post("/shipper/orders/{id}/assignment", order.getId())
                .param("decision", "accept")
                .with(csrf()).with(user(replacement.getUsername()).roles("SHIPPER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/shipper"));
        assertEquals(ShipperAssignmentStatus.ACCEPTED, order.getShipperAssignmentStatus());
        orderService.shipperUpdate(replacement, order.getId(), OrderStatus.PICKED_UP);
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
    }

    @Test
    void acceptedShipperCannotBeReassignedFromManagementOrAStaleForm() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("accepted-admin-" + suffix, Role.ADMIN);
        User manager = saveUser("accepted-manager-" + suffix, Role.MANAGER);
        User first = saveUser("accepted-first-" + suffix, Role.SHIPPER);
        User second = saveUser("accepted-second-" + suffix, Role.SHIPPER);
        User customer = saveUser("accepted-buyer-" + suffix, Role.USER);
        User vendor = saveUser("accepted-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Accepted");
        shop.setSlug("accepted-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        managerShops.save(assignment);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        for (OrderStatus stage : java.util.List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP)) {
            CustomerOrder order = saveOrder("ACC-" + stage.ordinal() + "-" + suffix, null, customer, shop, stage);
            orderService.assignShipper(admin, order.getId(), first.getId());
            String pendingPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                    .with(user(admin.getUsername()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(pendingPage.contains("Đổi shipper"));

            orderService.respondToShipperAssignment(first, order.getId(), true, null);
            for (User actor : java.util.List.of(admin, manager)) {
                String acceptedPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                        .with(user(actor.getUsername()).roles(actor.getRole().name())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertTrue(acceptedPage.contains("Shipper đã chấp nhận"));
                assertTrue(!acceptedPage.contains("Đổi shipper"));
                mvc.perform(post("/management/orders/{id}/assign", order.getId())
                        .param("shipperId", second.getId().toString()).with(csrf())
                        .with(user(actor.getUsername()).roles(actor.getRole().name())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/management/orders"))
                    .andExpect(flash().attribute("error", "Shipper đã chấp nhận đơn, không thể đổi shipper."));
                assertEquals(first.getId(), order.getShipper().getId());
                assertEquals(ShipperAssignmentStatus.ACCEPTED, order.getShipperAssignmentStatus());
            }
        }
    }

    @Test
    void reassigningAndCancellingAnOrderNotifiesAffectedShippers() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("dispatch-admin-" + suffix, Role.ADMIN);
        User first = saveUser("dispatch-first-" + suffix, Role.SHIPPER);
        User second = saveUser("dispatch-second-" + suffix, Role.SHIPPER);
        User customer = saveUser("dispatch-buyer-" + suffix, Role.USER);
        User vendor = saveUser("dispatch-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Dispatch");
        shop.setSlug("dispatch-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("DISPATCH-" + suffix, null, customer, shop, OrderStatus.CONFIRMED);

        orderService.assignShipper(admin, order.getId(), first.getId());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String assignedPage = mvc.perform(get("/shipper").param("q", order.getCode())
                .with(user(first.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(assignedPage.contains("Khi cửa hàng báo sẵn sàng"));
        assertTrue(assignedPage.contains("Chấp nhận đơn"));
        assertTrue(assignedPage.contains("Từ chối đơn"));

        orderService.assignShipper(admin, order.getId(), second.getId());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(first.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Phân công giao hàng đã thay đổi")
                && n.getMessage().contains(order.getCode())));

        orderService.vendorUpdate(vendor, order.getId(), OrderStatus.CANCELLED);
        assertEquals(ShipperAssignmentStatus.CANCELLED, order.getShipperAssignmentStatus());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(second.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Đơn giao hàng đã bị hủy")
                && n.getMessage().contains(order.getCode())));
        String managementPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managementPage.contains("Phân công đã hủy"));
    }

    @Test
    void failedDeliveryCanBeAssignedBackToTheSameShipper() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("retry-admin-" + suffix, Role.ADMIN);
        User shipper = saveUser("retry-shipper-" + suffix, Role.SHIPPER);
        User customer = saveUser("retry-buyer-" + suffix, Role.USER);
        User vendor = saveUser("retry-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Retry");
        shop.setSlug("retry-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("RETRY-" + suffix, shipper, customer, shop, OrderStatus.DELIVERY_FAILED);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String managementPage = mvc.perform(get("/management/orders").param("q", order.getCode())
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managementPage.contains(shipper.getFullName() + " (shipper trước)"));

        orderService.retryFailedDelivery(admin, order.getId(), shipper.getId());

        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
        assertEquals(shipper.getId(), order.getShipper().getId());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(shipper.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Đơn giao lại được phân công")));
        String pendingPage = mvc.perform(get("/shipper").param("q", order.getCode())
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(pendingPage.contains("Chấp nhận đơn"));
        assertTrue(!pendingPage.contains("name=\"status\" value=\"SHIPPING\""));
        orderService.respondToShipperAssignment(shipper, order.getId(), true, null);
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.SHIPPING);
        assertEquals(OrderStatus.SHIPPING, order.getStatus());
    }

    @Test
    void unansweredAssignmentsAreRemindedThenReturnedToDispatch() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("timeout-admin-" + suffix, Role.ADMIN);
        User shipper = saveUser("timeout-shipper-" + suffix, Role.SHIPPER);
        User customer = saveUser("timeout-buyer-" + suffix, Role.USER);
        User vendor = saveUser("timeout-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Timeout");
        shop.setSlug("timeout-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("TIMEOUT-" + suffix, null, customer, shop, OrderStatus.READY_FOR_PICKUP);

        orderService.assignShipper(admin, order.getId(), shipper.getId());
        order.setShipperAssignedAt(LocalDateTime.now().minusMinutes(11));
        orderService.checkPendingShipperAssignments();
        assertTrue(order.getShipperReminderSentAt() != null);
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(shipper.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Nhắc phản hồi đơn giao")));

        order.setShipperAssignedAt(LocalDateTime.now().minusMinutes(31));
        orderService.checkPendingShipperAssignments();
        assertNull(order.getShipper());
        assertEquals(ShipperAssignmentStatus.EXPIRED, order.getShipperAssignmentStatus());
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(admin.getId(), PageRequest.of(0, 10))
            .stream().anyMatch(n -> n.getTitle().equals("Shipper chưa phản hồi phân công")));

        CustomerOrder legacy = saveOrder("TIMEOUT-LEGACY-" + suffix, shipper, customer, shop,
            OrderStatus.READY_FOR_PICKUP);
        orderService.checkPendingShipperAssignments();
        assertEquals(ShipperAssignmentStatus.PENDING, legacy.getShipperAssignmentStatus());
        assertTrue(legacy.getShipperAssignedAt() != null);

        CustomerOrder directRetry = saveOrder("TIMEOUT-RETRY-" + suffix, shipper, customer, shop,
            OrderStatus.DELIVERY_FAILED);
        orderService.retryFailedDelivery(admin, directRetry.getId(), shipper.getId());
        directRetry.setShipperAssignedAt(LocalDateTime.now().minusMinutes(31));
        orderService.checkPendingShipperAssignments();
        assertEquals(ShipperAssignmentStatus.EXPIRED, directRetry.getShipperAssignmentStatus());
        assertEquals(shipper.getId(), directRetry.getShipper().getId());
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, directRetry.getId(), OrderStatus.SHIPPING));
        orderService.confirmReturnedToShop(admin, directRetry.getId());
        assertEquals(OrderStatus.READY_FOR_PICKUP, directRetry.getStatus());
        assertNull(directRetry.getShipper());
        orderService.assignShipper(admin, directRetry.getId(), shipper.getId());
        assertEquals(ShipperAssignmentStatus.PENDING, directRetry.getShipperAssignmentStatus());
    }

    @Test
    void directRetryCannotBeRejectedUntilGoodsReturnToShop() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = saveUser("return-admin-" + suffix, Role.ADMIN);
        User shipper = saveUser("return-shipper-" + suffix, Role.SHIPPER);
        User customer = saveUser("return-buyer-" + suffix, Role.USER);
        User vendor = saveUser("return-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Return");
        shop.setSlug("return-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        CustomerOrder order = saveOrder("RETURN-" + suffix, shipper, customer, shop, OrderStatus.DELIVERY_FAILED);
        order.setFailureReason("Khách không nghe máy");
        order.setDeliveryProofUrl("private:delivery-proofs/previous-attempt.jpg");

        orderService.retryFailedDelivery(admin, order.getId(), shipper.getId());
        assertEquals(OrderStatus.PICKED_UP, order.getStatus());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String customerPage = mvc.perform(get("/orders/{id}", order.getId())
                .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(customerPage.contains("Xem ảnh lần giao thất bại"));
        assertTrue(!customerPage.contains("<strong>Giao thất bại:</strong>"));
        assertThrows(IllegalArgumentException.class,
            () -> orderService.respondToShipperAssignment(shipper, order.getId(), false, "Không thể giao lại"));
        orderService.confirmReturnedToShop(shipper, order.getId());
        assertEquals(OrderStatus.READY_FOR_PICKUP, order.getStatus());
        orderService.respondToShipperAssignment(shipper, order.getId(), false, "Không thể giao lại");
        assertNull(order.getShipper());
        assertEquals(ShipperAssignmentStatus.REJECTED, order.getShipperAssignmentStatus());
    }

    @Test
    void searchAndDashboardOnlyShowAssignedOrders() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User shipper = saveUser("shipper-board-" + suffix, Role.SHIPPER);
        User otherShipper = saveUser("shipper-other-" + suffix, Role.SHIPPER);
        User customer = saveUser("shipper-buyer-" + suffix, Role.USER);
        User vendor = saveUser("shipper-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Dashboard");
        shop.setSlug("shipper-dashboard-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);

        CustomerOrder assigned = saveOrder("SHIP-OWN-" + suffix, shipper, customer, shop, OrderStatus.DELIVERED);
        assigned.setDeliveredAt(LocalDateTime.of(2026, 10, 16, 9, 24));
        assigned.setDeliveryProofUrl("private:delivery-proofs/proof.jpg");
        orders.save(assigned);
        CustomerOrder hidden = saveOrder("SHIP-HIDDEN-" + suffix, otherShipper, customer, shop, OrderStatus.READY_FOR_PICKUP);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String page = mvc.perform(get("/shipper").param("q", "SHIP-")
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains(assigned.getCode()));
        assertTrue(!page.contains(hidden.getCode()));
        assertTrue(page.contains("maps/search/?api=1"));
        assertTrue(page.contains("data-copy-address"));
        assertTrue(page.contains("data-address=\"01 Vo Van Ngan, Thu Duc\""));
        assertTrue(page.contains("Giao thành công lúc"));
        assertTrue(page.contains("Ảnh giao thành công"));
        assertTrue(page.contains("Chờ lấy") && page.contains("Đang giao")
            && page.contains("Hoàn tất") && page.contains("Giao thất bại"));

        String filtered = mvc.perform(get("/shipper").param("status", "READY_FOR_PICKUP")
                .param("q", "SHIP-").with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(!filtered.contains(assigned.getCode()));
        assertTrue(!filtered.contains(hidden.getCode()));

        assertEquals(0, orderService.forShipper(shipper, null, hidden.getCode(), 0).getTotalElements());
        assertEquals(0, orderService.forShipper(shipper, null, "%", 0).getTotalElements());
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, hidden.getId(), OrderStatus.PICKED_UP));
        mvc.perform(post("/shipper/orders/{id}/status", hidden.getId())
                .param("status", "PICKED_UP").with(csrf())
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/shipper"));
        assertEquals(OrderStatus.READY_FOR_PICKUP, hidden.getStatus());
    }

    @Test
    void sortsAssignedOrdersByDeliveryEventAndKeepsFilters() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User shipper = saveUser("shipper-sort-" + suffix, Role.SHIPPER);
        User otherShipper = saveUser("shipper-sort-other-" + suffix, Role.SHIPPER);
        User customer = saveUser("shipper-sort-buyer-" + suffix, Role.USER);
        User vendor = saveUser("shipper-sort-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Sort");
        shop.setSlug("shipper-sort-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);

        CustomerOrder first = saveOrder("SORT-OLD-" + suffix, shipper, customer, shop, OrderStatus.DELIVERED);
        first.setDeliveredAt(LocalDateTime.of(2026, 10, 16, 8, 30));
        orders.save(first);
        CustomerOrder second = saveOrder("SORT-NEW-" + suffix, shipper, customer, shop, OrderStatus.DELIVERED);
        second.setDeliveredAt(LocalDateTime.of(2026, 10, 16, 9, 30));
        orders.save(second);
        CustomerOrder hidden = saveOrder("SORT-HIDDEN-" + suffix, otherShipper, customer, shop, OrderStatus.DELIVERED);
        hidden.setDeliveredAt(LocalDateTime.of(2026, 10, 16, 10, 30));
        orders.save(hidden);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String newest = mvc.perform(get("/shipper").param("q", "SORT-").param("status", "DELIVERED")
                .param("sort", "recent").with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(newest.indexOf(second.getCode()) < newest.indexOf(first.getCode()));
        assertTrue(!newest.contains(hidden.getCode()));
        assertTrue(newest.contains("sort=recent"));

        String oldest = mvc.perform(get("/shipper").param("q", "SORT-").param("status", "DELIVERED")
                .param("sort", "oldest").with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(oldest.indexOf(first.getCode()) < oldest.indexOf(second.getCode()));
        assertTrue(oldest.contains("sort=oldest"));
        assertEquals(2, orderService.forShipper(shipper, OrderStatus.DELIVERED, "SORT-", 0, "oldest")
            .getTotalElements());
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return users.save(user);
    }

    private CustomerOrder saveOrder(String code, User shipper, User customer, Shop shop, OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setUser(customer);
        order.setShop(shop);
        order.setShipper(shipper);
        order.setStatus(status);
        order.setReceiverName("Người nhận");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Vo Van Ngan, Thu Duc");
        order.setSubtotal(new BigDecimal("30000"));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("30000"));
        return orders.save(order);
    }
}
