package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementService;

/** Ensures list filters AND mutations enforce ManagerShop boundaries, not only UI hiding. */
@ActiveProfiles("test") @SpringBootTest @Transactional
class ManagementPromotionScopeTests {
    @Autowired ManagementService management;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired ManagerShopRepository assignments;
    @Autowired PromotionRepository promotions;
    @Autowired OrderRepository orders;
    @Test void managerCannotReadOrCreatePromotionForUnassignedShopOrListItsOrders() {
        var admin=Step34Fixtures.user(users,Role.ADMIN);
        var manager=Step34Fixtures.user(users,Role.MANAGER);
        var buyer=Step34Fixtures.user(users,Role.USER);
        var assigned=Step34Fixtures.shop(users,shops);
        var outside=Step34Fixtures.shop(users,shops);
        Step34Fixtures.assign(assignments,admin,manager,assigned);
        var form=form();
        management.savePromotion(null,assigned.getId(),form,manager);
        var saved=promotions.findAll().stream().filter(p->p.getShop()!=null && p.getShop().getId().equals(assigned.getId())).findFirst().orElseThrow();
        assertThat(management.promotions(manager,null,null,null,0).getContent())
            .extracting(Promotion::getId).contains(saved.getId());
        assertThat(management.promotions(manager,null,outside.getId(),null,0)).isEmpty();
        assertThatThrownBy(()->management.savePromotion(null,outside.getId(),form,manager))
            .isInstanceOf(AccessDeniedException.class);
        var visible=Step34Fixtures.order(orders,buyer,assigned,OrderStatus.NEW);
        var hidden=Step34Fixtures.order(orders,buyer,outside,OrderStatus.NEW);
        assertThat(management.orders(manager,null,null,null,0).getContent())
            .extracting(CustomerOrder::getId).contains(visible.getId()).doesNotContain(hidden.getId());
        assertThatThrownBy(()->management.orders(manager,null,outside.getId(),null,0))
            .isInstanceOf(AccessDeniedException.class);
    }
    private PromotionForm form() {
        PromotionForm f=new PromotionForm();
        f.setName("Ưu đãi Step 34");f.setType(PromotionType.SHIPPING_DISCOUNT);
        f.setDiscountValue(new BigDecimal("5000"));
        f.setStartsAt(LocalDateTime.now().minusDays(1));
        f.setEndsAt(LocalDateTime.now().plusDays(2)); f.setActive(true);
        return f;
    }
}
