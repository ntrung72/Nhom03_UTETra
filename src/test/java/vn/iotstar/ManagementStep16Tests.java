package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CarrierForm;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;

/** Step 16 only: carriers and shop commission; no CustomerOrder/Checkout classes. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep16Tests {
    @Autowired ManagementService management;
    @Autowired CarrierRepository carriers;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;

    private User admin;
    private User manager;
    private User buyer;

    @BeforeEach
    void setUp() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        buyer = user(Role.USER);
    }

    @Test
    void carrierLifecycleAndActiveListForUpcomingCheckout() {
        String name = unique("Carrier");
        CarrierForm form = form(name, "15000.00");
        management.saveCarrier(admin, null, form);
        Carrier created = carriers.findAll().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
        assertThat(management.carriers(admin, name, 0).getContent()).extracting(Carrier::getId).contains(created.getId());
        assertThat(carriers.findByActiveTrueOrderByShippingFeeAsc()).extracting(Carrier::getId).contains(created.getId());

        form.setShippingFee(new BigDecimal("5000.00"));
        management.saveCarrier(manager, created.getId(), form);
        assertThat(management.getCarrier(manager, created.getId()).getShippingFee()).isEqualByComparingTo("5000");

        management.toggleCarrier(manager, created.getId());
        assertThat(carriers.findByActiveTrueOrderByShippingFeeAsc()).extracting(Carrier::getId).doesNotContain(created.getId());
        management.toggleCarrier(admin, created.getId());
        assertThat(carriers.findByActiveTrueOrderByShippingFeeAsc()).extracting(Carrier::getId).contains(created.getId());

        management.deleteCarrier(admin, created.getId());
        assertThat(carriers.findById(created.getId())).isEmpty();
    }

    @Test
    void rejectsDuplicateOrInvalidCarrierNamesAndFees() {
        String name = unique("Carrier");
        CarrierForm form = form(name, "0");
        management.saveCarrier(admin, null, form);
        assertThatThrownBy(() -> management.saveCarrier(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setName(" ");
        assertThatThrownBy(() -> management.saveCarrier(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setName(unique("Carrier"));
        form.setShippingFee(new BigDecimal("-0.01"));
        assertThatThrownBy(() -> management.saveCarrier(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setShippingFee(new BigDecimal("1.001"));
        assertThatThrownBy(() -> management.saveCarrier(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyManagementRolesCanReadOrMutateCarrier() {
        assertThatThrownBy(() -> management.carriers(buyer, null, 0))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.saveCarrier(buyer, null, form(unique("Invalid"), "1000")))
            .isInstanceOf(AccessDeniedException.class);
        String validName = unique("Valid");
        management.saveCarrier(admin, null, form(validName, "0"));
        Long id = carriers.findAll().stream()
            .filter(c -> c.getName().equals(validName)).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> management.toggleCarrier(buyer, id))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.deleteCarrier(buyer, id))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void commissionRateIsShopScopedAndHistoricalSnapshotIsNotRetroactivelyChanged() {
        User owner = user(Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(unique("Shop"));
        shop.setSlug(UUID.randomUUID().toString());
        shop.setStatus(ShopStatus.PENDING);
        shop = shops.saveAndFlush(shop);
        Long shopId = shop.getId();
        management.updateShop(admin, shopId, ShopStatus.APPROVED, new BigDecimal("5.00"));
        BigDecimal snapshotAtCheckout = shops.findById(shopId).orElseThrow().getCommissionRate();
        assertThatThrownBy(() -> management.updateShop(manager, shopId, ShopStatus.APPROVED, new BigDecimal("8.00")))
            .isInstanceOf(AccessDeniedException.class);
        management.updateShop(admin, shopId, ShopStatus.APPROVED, new BigDecimal("8.00"));
        assertThat(shops.findById(shopId).orElseThrow().getCommissionRate()).isEqualByComparingTo("8");
        assertThat(snapshotAtCheckout).isEqualByComparingTo("5");
        assertThatThrownBy(() -> management.updateShop(admin, shopId, ShopStatus.APPROVED, new BigDecimal("100.01")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private CarrierForm form(String name, String fee) {
        CarrierForm f = new CarrierForm();
        f.setName(name);
        f.setShippingFee(new BigDecimal(fee));
        f.setActive(true);
        return f;
    }

    private User user(Role role) {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setUsername("s16" + uid);
        u.setEmail("s16" + uid + "@example.invalid");
        u.setFullName("Step 16 " + uid);
        u.setPasswordHash("test-hash");
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 12);
    }
}
