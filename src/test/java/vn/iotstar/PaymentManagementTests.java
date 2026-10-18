package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementService;

/** The reference payment tests were Admin-only; Word and Group03 require ManagerShop scoping. */
@ActiveProfiles("test") @SpringBootTest @Transactional
class PaymentManagementTests {
    @Autowired ManagementService management;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired ManagerShopRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired PaymentTransactionRepository payments;
    @Autowired WebApplicationContext web;

    @Test void adminSeesAllButManagerOnlyAssignedShopAndCannotForgeShopId() throws Exception {
        var admin=Step34Fixtures.user(users,Role.ADMIN);
        var manager=Step34Fixtures.user(users,Role.MANAGER);
        var buyer=Step34Fixtures.user(users,Role.USER);
        var assigned=Step34Fixtures.shop(users,shops);
        var denied=Step34Fixtures.shop(users,shops);
        Step34Fixtures.assign(assignments,admin,manager,assigned);
        var first=transaction(Step34Fixtures.order(orders,buyer,assigned,OrderStatus.NEW),PaymentTransactionStatus.SUCCESS);
        var second=transaction(Step34Fixtures.order(orders,buyer,denied,OrderStatus.NEW),PaymentTransactionStatus.FAILED);
        assertThat(management.payments(admin,null,null,null,0).getContent()).extracting(PaymentTransaction::getId)
            .contains(first.getId(),second.getId());
        assertThat(management.payments(manager,null,null,null,0).getContent()).extracting(PaymentTransaction::getId)
            .contains(first.getId()).doesNotContain(second.getId());
        assertThat(management.payments(manager,null,PaymentTransactionStatus.SUCCESS,assigned.getId(),0).getContent())
            .extracting(PaymentTransaction::getId).contains(first.getId());
        assertThatThrownBy(()->management.payments(manager,null,null,denied.getId(),0))
            .isInstanceOf(AccessDeniedException.class);
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/management/payments").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/payments").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/payments").with(user(buyer.getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection());
    }
    private PaymentTransaction transaction(CustomerOrder order,PaymentTransactionStatus status) {
        PaymentTransaction t=new PaymentTransaction();t.setOrder(order);
        t.setTransactionCode("PAY"+Step34Fixtures.uid());
        t.setCheckoutReference("REF"+Step34Fixtures.uid());
        t.setAmount(new BigDecimal("100000"));t.setClientIp("127.0.0.1");t.setStatus(status);
        return payments.saveAndFlush(t);
    }
}
