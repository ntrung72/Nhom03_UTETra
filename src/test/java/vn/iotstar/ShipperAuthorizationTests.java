package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.OrderService;

/** Step 34: authorization & dashboard regression, adapted from UTETra's ShipperDashboardTests. */
@ActiveProfiles("test") @SpringBootTest @Transactional
@TestPropertySource(properties="app.upload-dir=${java.io.tmpdir}/utetra-step34-proofs")
class ShipperAuthorizationTests {
    @Autowired WebApplicationContext web;
    @Autowired OrderService service;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired OrderRepository orders;
    @Autowired ManagerShopRepository assignments;

    @Test void shipperDashboardOnlyListsAssignedOrdersAndBlocksUnauthorizedStatusPost() throws Exception {
        var admin=Step34Fixtures.user(users,Role.ADMIN);
        var shipper=Step34Fixtures.user(users,Role.SHIPPER);
        var otherShipper=Step34Fixtures.user(users,Role.SHIPPER);
        var customer=Step34Fixtures.user(users,Role.USER);
        var shop=Step34Fixtures.shop(users,shops);
        CustomerOrder own=Step34Fixtures.order(orders,customer,shop,OrderStatus.READY_FOR_PICKUP);
        CustomerOrder other=Step34Fixtures.order(orders,customer,shop,OrderStatus.READY_FOR_PICKUP);
        service.assignShipper(admin,own.getId(),shipper.getId());
        service.assignShipper(admin,other.getId(),otherShipper.getId());
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        String html=mvc.perform(get("/shipper").with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(own.getCode()).doesNotContain(other.getCode());
        assertThat(service.forShipper(shipper,OrderStatus.READY_FOR_PICKUP,0).getContent())
            .extracting(CustomerOrder::getId).contains(own.getId()).doesNotContain(other.getId());
        assertThatThrownBy(()->service.shipperUpdate(shipper,other.getId(),OrderStatus.PICKED_UP,null,null))
            .isInstanceOf(AccessDeniedException.class);
        mvc.perform(post("/shipper/orders/{id}/status",other.getId())
                .with(user(customer.getUsername()).roles("USER")).with(csrf()).param("status","PICKED_UP"))
            .andExpect(status().is3xxRedirection());
        assertThat(orders.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        mvc.perform(get("/shipper").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().is3xxRedirection());
    }

    @Test void failedDeliveryNeedsReasonAndRetryMustUseAnotherShipper() {
        var admin=Step34Fixtures.user(users,Role.ADMIN);
        var shipper=Step34Fixtures.user(users,Role.SHIPPER);
        var other=Step34Fixtures.user(users,Role.SHIPPER);
        var customer=Step34Fixtures.user(users,Role.USER);
        var shop=Step34Fixtures.shop(users,shops);
        CustomerOrder order=Step34Fixtures.order(orders,customer,shop,OrderStatus.READY_FOR_PICKUP);
        service.assignShipper(admin,order.getId(),shipper.getId());
        assertThatThrownBy(()->service.shipperUpdate(shipper,order.getId(),OrderStatus.DELIVERED,null,null))
            .isInstanceOf(IllegalArgumentException.class);
        service.shipperUpdate(shipper,order.getId(),OrderStatus.PICKED_UP,null,null);
        service.shipperUpdate(shipper,order.getId(),OrderStatus.SHIPPING,null,null);
        assertThatThrownBy(()->service.shipperUpdate(shipper,order.getId(),OrderStatus.DELIVERY_FAILED,"x",null))
            .isInstanceOf(IllegalArgumentException.class);
        service.shipperUpdate(shipper,order.getId(),OrderStatus.DELIVERY_FAILED,"Khách không nhận hàng",null);
        assertThat(order.getFailureReason()).contains("Khách không nhận");
        assertThatThrownBy(()->service.retryFailedDelivery(admin,order.getId(),shipper.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        service.retryFailedDelivery(admin,order.getId(),other.getId());
        assertThat(order.getShipper().getId()).isEqualTo(other.getId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(order.getFailureReason()).isNull();
    }
}
