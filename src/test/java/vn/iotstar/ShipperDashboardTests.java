package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.OrderService;

/** TV3 Bước 43: Shipper query/sort + backend assignment privacy. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipperDashboardTests {
    @Autowired OrderService delivery;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired WebApplicationContext web;
    User shipper, stranger, customer;
    Shop shop;

    @BeforeEach void before() {
        shipper = account(Role.SHIPPER);
        stranger = account(Role.SHIPPER);
        customer = account(Role.USER);
        shop = shop();
    }

    @Test void searchByCodeAndStatusOnlyShowsAssignedOrders() {
        var assigned = order(shipper, OrderStatus.SHIPPING, "MATCH-S43", LocalDateTime.now().minusHours(2));
        var someoneElse = order(stranger, OrderStatus.SHIPPING, "MATCH-OTHER", LocalDateTime.now());
        order(shipper, OrderStatus.DELIVERED, "UNRELATED", LocalDateTime.now());
        var page = delivery.forShipper(shipper, OrderStatus.SHIPPING, "MATCH", 0, "recent");
        assertThat(page.getContent()).extracting(CustomerOrder::getId)
            .containsExactly(assigned.getId()).doesNotContain(someoneElse.getId());
        assertThat(delivery.forShipper(shipper, null, "NO-ORDER", 0, "recent").getContent()).isEmpty();
    }

    @Test void sortChronologicallyAndEscapeWildcards() {
        var older = order(shipper, OrderStatus.SHIPPING, "SORT-OLD", LocalDateTime.now().minusHours(4));
        var newer = order(shipper, OrderStatus.SHIPPING, "SORT-NEW", LocalDateTime.now().minusMinutes(8));
        assertThat(delivery.forShipper(shipper, null, "SORT", 0, "recent").getContent())
            .extracting(CustomerOrder::getId).containsExactly(newer.getId(), older.getId());
        assertThat(delivery.forShipper(shipper, null, "SORT", 0, "oldest").getContent())
            .extracting(CustomerOrder::getId).containsExactly(older.getId(), newer.getId());
        assertThat(delivery.forShipper(shipper, null, "%", 0, "recent").getContent()).isEmpty();
    }

    @Test void unassignedShipperCannotReadOthersOrUpdate() {
        CustomerOrder assigned = order(shipper, OrderStatus.READY_FOR_PICKUP, "S43-PRIVATE", LocalDateTime.now());
        assertThat(delivery.forShipper(stranger, null, "S43-PRIVATE", 0, "recent").getContent()).isEmpty();
        assertThatThrownBy(() -> delivery.shipperUpdate(stranger, assigned.getId(), OrderStatus.PICKED_UP, null, null))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test void dashboardSupportsQuerySortAndOnlyAuthorizedShipper() throws Exception {
        order(shipper, OrderStatus.SHIPPING, "S43-VIEW", LocalDateTime.now());
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/shipper?q=S43-VIEW&sort=oldest&status=SHIPPING")
                .with(user(shipper.getUsername()).roles("SHIPPER")))
            .andExpect(status().isOk());
        mvc.perform(get("/shipper?q=S43-VIEW")
                .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection());
    }

    private User account(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setUsername("s43s" + id);
        u.setEmail("s43s" + id + "@example.invalid");
        u.setPasswordHash("test-hash");
        u.setFullName("S43 " + id);
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setOwner(account(Role.VENDOR));
        s.setName("Shipper shop " + UUID.randomUUID());
        s.setSlug(UUID.randomUUID().toString());
        s.setStatus(ShopStatus.APPROVED);
        s.setCommissionRate(BigDecimal.TEN);
        return shops.saveAndFlush(s);
    }

    private CustomerOrder order(User assigned, OrderStatus status, String marker, LocalDateTime pickup) {
        CustomerOrder o = new CustomerOrder();
        o.setCode("S43" + UUID.randomUUID().toString().replace("-", "").substring(0, 15) + marker);
        o.setUser(customer);
        o.setShop(shop);
        o.setShipper(assigned);
        o.setStatus(status);
        o.setPickedUpAt(pickup);
        o.setReceiverName("Khách giao");
        o.setReceiverPhone("0900000000");
        o.setShippingAddress("Địa chỉ kiểm thử");
        o.setSubtotal(new BigDecimal("100000"));
        o.setTotalAmount(new BigDecimal("100000"));
        o.setProductDiscount(BigDecimal.ZERO);
        o.setVoucherDiscount(BigDecimal.ZERO);
        o.setShippingDiscount(BigDecimal.ZERO);
        o.setFinalShippingFee(BigDecimal.ZERO);
        return orders.saveAndFlush(o);
    }
}
