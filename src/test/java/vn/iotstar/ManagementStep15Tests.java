package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.entity.User;
import vn.iotstar.entity.Voucher;
import vn.iotstar.entity.VoucherUsage;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.repository.VoucherRepository;
import vn.iotstar.repository.VoucherUsageRepository;
import vn.iotstar.service.ManagementService;

/** Step 15: Admin voucher lifecycle and usage history; no checkout/Order module. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep15Tests {
    @Autowired ManagementService management;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherUsageRepository usages;
    @Autowired UserRepository users;
    User admin, manager, buyer;

    @BeforeEach
    void init() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        buyer = user(Role.USER);
    }

    @Test
    void adminCanCreateFilterEditToggleAndDelete() {
        VoucherForm form = form();
        management.saveVoucher(admin, null, form);
        Voucher saved = vouchers.findByCodeIgnoreCase(form.getCode()).orElseThrow();
        assertThat(saved.getCode()).isEqualTo(form.getCode().toUpperCase());
        assertThat(management.vouchers(admin, form.getCode(), VoucherType.PERCENT, true, 0)
            .getContent()).extracting(Voucher::getId).contains(saved.getId());
        form.setName("Updated " + form.getName());
        form.setDiscountValue(new BigDecimal("30"));
        management.saveVoucher(admin, saved.getId(), form);
        assertThat(management.getVoucher(admin, saved.getId()).getDiscountValue())
            .isEqualByComparingTo("30");
        management.toggleVoucher(admin, saved.getId());
        assertThat(management.getVoucher(admin, saved.getId()).isActive()).isFalse();
        management.deleteVoucher(admin, saved.getId());
        assertThat(vouchers.findById(saved.getId())).isEmpty();
    }

    @Test
    void validationRejectsInvalidOrDuplicateVoucher() {
        VoucherForm form = form();
        management.saveVoucher(admin, null, form);
        assertThatThrownBy(() -> management.saveVoucher(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setCode("DIFFERENT" + UUID.randomUUID().toString().substring(0, 7));
        form.setDiscountValue(new BigDecimal("101"));
        assertThatThrownBy(() -> management.saveVoucher(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setDiscountValue(new BigDecimal("20"));
        form.setEndsAt(form.getStartsAt().minusMinutes(1));
        assertThatThrownBy(() -> management.saveVoucher(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
        form.setEndsAt(form.getStartsAt().plusDays(5));
        form.setMaxUsesPerUser(0);
        assertThatThrownBy(() -> management.saveVoucher(admin, null, form))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managerCannotAdministerGlobalVouchersEvenThroughService() {
        VoucherForm form = form();
        assertThatThrownBy(() -> management.saveVoucher(manager, null, form))
            .isInstanceOf(AccessDeniedException.class);
        management.saveVoucher(admin, null, form);
        Voucher voucher = vouchers.findByCodeIgnoreCase(form.getCode()).orElseThrow();
        assertThatThrownBy(() -> management.vouchers(manager, null, null, null, 0))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.getVoucher(manager, voucher.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.toggleVoucher(manager, voucher.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.deleteVoucher(manager, voucher.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.voucherUsages(manager, voucher.getId(), 0))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void usageHistoryAndCheckoutLockPreparedWithoutCreatingOrders() {
        VoucherForm form = form();
        management.saveVoucher(admin, null, form);
        Voucher voucher = vouchers.findByCodeIgnoreCase(form.getCode()).orElseThrow();
        VoucherUsage usage = new VoucherUsage();
        usage.setUser(buyer);
        usage.setVoucher(voucher);
        usage.setCheckoutReference("s15-" + UUID.randomUUID());
        usage.setStatus(VoucherUsageStatus.USED);
        usages.saveAndFlush(usage);
        assertThat(management.voucherUsages(admin, voucher.getId(), 0).getTotalElements()).isEqualTo(1);
        assertThat(vouchers.findByCodeForCheckout(voucher.getCode())).isPresent();
        assertThat(vouchers.findByIdForUpdate(voucher.getId())).isPresent();
        assertThatThrownBy(() -> management.deleteVoucher(admin, voucher.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        form.setCode("CHANGED" + UUID.randomUUID().toString().substring(0, 6));
        assertThatThrownBy(() -> management.saveVoucher(admin, voucher.getId(), form))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(voucher.canApply(new BigDecimal("100000"), LocalDateTime.now())).isTrue();
        assertThat(voucher.calculateDiscount(new BigDecimal("100000"), new BigDecimal("10000")))
            .isEqualByComparingTo("20000");
    }

    private User user(Role role) {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("s15" + uid);
        user.setEmail("s15" + uid + "@example.invalid");
        user.setFullName("Step 15 " + uid);
        user.setPasswordHash("test-hash");
        user.setRole(role);
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }

    private VoucherForm form() {
        VoucherForm form = new VoucherForm();
        form.setCode("s15" + UUID.randomUUID().toString().substring(0, 9));
        form.setName("Voucher Step 15");
        form.setType(VoucherType.PERCENT);
        form.setDiscountValue(new BigDecimal("20"));
        form.setMinimumOrder(BigDecimal.ZERO);
        form.setQuantity(100);
        form.setMaxUsesPerUser(1);
        form.setStartsAt(LocalDateTime.now().minusDays(1));
        form.setEndsAt(LocalDateTime.now().plusDays(1));
        form.setActive(true);
        return form;
    }
}
