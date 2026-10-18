package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementService;

/** Reference-derived voucher regression cases adapted to Group03's Admin-only policy. */
@ActiveProfiles("test") @SpringBootTest @Transactional
class VoucherManagementTests {
    @Autowired ManagementService management;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherUsageRepository usages;
    @Autowired UserRepository users;
    @Autowired WebApplicationContext web;

    @Test void adminCreatesAndTogglesWhileManagerAndVendorCannotAccess() throws Exception {
        User admin=Step34Fixtures.user(users,Role.ADMIN);
        User manager=Step34Fixtures.user(users,Role.MANAGER);
        User vendor=Step34Fixtures.user(users,Role.VENDOR);
        VoucherForm form=validForm();
        management.saveVoucher(admin,null,form);
        var voucher=vouchers.findByCodeIgnoreCase(form.getCode()).orElseThrow();
        assertThat(management.vouchers(admin,form.getCode(),VoucherType.PERCENT,true,0).getContent())
            .extracting(Voucher::getId).contains(voucher.getId());
        assertThatThrownBy(()->management.toggleVoucher(manager,voucher.getId())).isInstanceOf(AccessDeniedException.class);
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/management/vouchers").with(user(admin.getUsername()).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/management/vouchers").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
        mvc.perform(post("/management/vouchers/"+voucher.getId()+"/toggle").with(user(manager.getUsername()).roles("MANAGER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        mvc.perform(get("/management/vouchers").with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().is3xxRedirection());
        management.toggleVoucher(admin,voucher.getId());
        assertThat(vouchers.findById(voucher.getId()).orElseThrow().isActive()).isFalse();
    }
    @Test void usageCannotBeDeletedOrRenamedAndPercentageIsValidated() {
        User admin=Step34Fixtures.user(users,Role.ADMIN);
        User buyer=Step34Fixtures.user(users,Role.USER);
        VoucherForm form=validForm();
        management.saveVoucher(admin,null,form);
        Voucher voucher=vouchers.findByCodeIgnoreCase(form.getCode()).orElseThrow();
        VoucherUsage usage=new VoucherUsage();
        usage.setVoucher(voucher); usage.setUser(buyer); usage.setStatus(VoucherUsageStatus.USED);
        usage.setCheckoutReference("s34-"+Step34Fixtures.uid());
        usages.saveAndFlush(usage);
        assertThat(management.voucherUsages(admin,voucher.getId(),0).getTotalElements()).isEqualTo(1);
        assertThatThrownBy(()->management.deleteVoucher(admin,voucher.getId())).isInstanceOf(IllegalArgumentException.class);
        form.setCode("NEW"+Step34Fixtures.uid());
        assertThatThrownBy(()->management.saveVoucher(admin,voucher.getId(),form)).isInstanceOf(IllegalArgumentException.class);
        form.setCode(voucher.getCode()); form.setDiscountValue(new BigDecimal("101"));
        assertThatThrownBy(()->management.saveVoucher(admin,voucher.getId(),form)).isInstanceOf(IllegalArgumentException.class);
    }
    private VoucherForm validForm() {
        VoucherForm f=new VoucherForm();
        f.setCode("V"+Step34Fixtures.uid()); f.setName("Kiểm thử quản lý voucher");
        f.setType(VoucherType.PERCENT); f.setDiscountValue(new BigDecimal("20"));
        f.setMinimumOrder(BigDecimal.ZERO); f.setMaxDiscount(new BigDecimal("20000"));
        f.setQuantity(12); f.setMaxUsesPerUser(1); f.setActive(true);
        f.setStartsAt(LocalDateTime.now().minusDays(1)); f.setEndsAt(LocalDateTime.now().plusDays(1));
        return f;
    }
}
