package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.repository.VoucherRepository;
import vn.iotstar.service.ManagementService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class VoucherManagementTests {
    @Autowired private ManagementService managementService;
    @Autowired private VoucherRepository voucherRepository;

    @Test
    void managerCanCreateToggleSearchAndDeleteUnusedVoucher() {
        VoucherForm form = validForm();
        form.setCode("  test-code  ");

        managementService.saveVoucher(null, form);
        var saved = voucherRepository.findByCodeIgnoreCase("TEST-CODE").orElseThrow();
        assertEquals("TEST-CODE", saved.getCode());
        assertTrue(managementService.vouchers("test-code", VoucherType.PERCENT, true, 0).hasContent());

        managementService.toggleVoucher(saved.getId());
        assertFalse(voucherRepository.findById(saved.getId()).orElseThrow().isActive());

        managementService.deleteVoucher(saved.getId());
        assertFalse(voucherRepository.existsById(saved.getId()));
    }

    @Test
    void percentageVoucherCannotExceedOneHundredPercent() {
        VoucherForm form = validForm();
        form.setCode("TOO-MUCH");
        form.setDiscountValue(new BigDecimal("101"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> managementService.saveVoucher(null, form));
        assertTrue(exception.getMessage().contains("100%"));
        assertFalse(voucherRepository.existsByCodeIgnoreCase("TOO-MUCH"));
    }

    private VoucherForm validForm() {
        VoucherForm form = new VoucherForm();
        form.setCode("TEST10");
        form.setName("Mã kiểm thử");
        form.setType(VoucherType.PERCENT);
        form.setDiscountValue(new BigDecimal("10"));
        form.setMinimumOrder(new BigDecimal("50000"));
        form.setMaxDiscount(new BigDecimal("20000"));
        form.setQuantity(10);
        form.setMaxUsesPerUser(1);
        form.setStartsAt(LocalDateTime.now().minusMinutes(1));
        form.setEndsAt(LocalDateTime.now().plusDays(7));
        form.setActive(true);
        return form;
    }
}
