package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
import vn.iotstar.service.*;

/** Reference-derived audit assertions adapted to Group03's scoped Manager audit policy. */
@ActiveProfiles("test") @SpringBootTest @Transactional
class AuditLogTests {
    @Autowired ManagementService management;
    @Autowired AuditLogService audit;
    @Autowired AuditLogRepository logs;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired ManagerShopRepository assignments;
    @Autowired WebApplicationContext web;

    @Test void recordsBeforeAfterAndManagerCannotSeeOtherShopsAuditOrGlobalEvents() throws Exception {
        var admin=Step34Fixtures.user(users,Role.ADMIN);
        var manager=Step34Fixtures.user(users,Role.MANAGER);
        var buyer=Step34Fixtures.user(users,Role.USER);
        var assigned=Step34Fixtures.shop(users,shops);
        var denied=Step34Fixtures.shop(users,shops);
        Step34Fixtures.assign(assignments,admin,manager,assigned);
        management.updateShop(admin,assigned.getId(),ShopStatus.APPROVED,new BigDecimal("7.00"));
        management.updateShop(admin,denied.getId(),ShopStatus.SUSPENDED,new BigDecimal("8.00"));
        management.toggleUser(buyer.getId(),admin);
        assertThat(logs.findAll()).extracting(AuditLog::getAction).contains(AuditAction.USER_LOCKED,AuditAction.SHOP_COMMISSION_CHANGED);
        assertThat(audit.search(manager,null,AuditAction.SHOP_COMMISSION_CHANGED,0).getContent())
            .extracting(AuditLog::getTargetId).contains(assigned.getId()).doesNotContain(denied.getId());
        assertThat(audit.search(admin,null,AuditAction.SHOP_COMMISSION_CHANGED,0).getContent())
            .extracting(AuditLog::getTargetId).contains(assigned.getId(),denied.getId());
        assertThat(audit.search(manager,null,AuditAction.USER_LOCKED,0)).isEmpty();
        assertThatThrownBy(()->audit.search(buyer,null,null,0)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->management.updateShop(manager,denied.getId(),ShopStatus.APPROVED,BigDecimal.ONE))
            .isInstanceOf(AccessDeniedException.class);
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/management/audit-logs").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/audit-logs").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/audit-logs").with(user(buyer.getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection());
    }
}
