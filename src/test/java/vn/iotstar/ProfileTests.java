package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.AddressForm;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.service.ProfileService;

@SpringBootTest(properties = "app.upload-dir=${java.io.tmpdir}/utetra-profile-tests")
@ActiveProfiles("test")
@Transactional
class ProfileTests extends CustomerTestFixtures {
    @Autowired ProfileService profile;
    @Autowired AddressRepository addresses;

    @Test
    void allProfileTabsRenderForCustomerWithCsrfForms() throws Exception {
        for (String tab : new String[]{"profile", "avatar", "bank", "addresses", "password"}) {
            var html = mvc.perform(get("/user/profile").with(user(customer.getUsername()).roles("USER")).param("tab", tab))
                .andExpect(status().isOk()).andExpect(view().name("user/profile"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).contains("_csrf", customer.getFullName());
        }
    }

    @Test
    void updatesOnlyEditableProfileFields() throws Exception {
        mvc.perform(post("/user/profile").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("fullName", "Nguyễn Huy Tân").param("phone", "0901234567")
            .param("role", "ADMIN").param("username", "changed"))
            .andExpect(status().is3xxRedirection());
        var saved = users.findById(customer.getId()).orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("Nguyễn Huy Tân");
        assertThat(saved.getPhone()).isEqualTo("0901234567");
        assertThat(saved.getRole().name()).isEqualTo("USER");
        assertThat(saved.getUsername()).isEqualTo(customer.getUsername());
    }

    @Test
    void invalidProfileAndMissingCsrfDoNotSave() throws Exception {
        mvc.perform(post("/user/profile").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("fullName", "").param("phone", "invalid"))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("profileForm", "fullName", "phone"));
        mvc.perform(post("/user/profile").with(user(customer.getUsername()).roles("USER"))
            .param("fullName", "Changed")) .andExpect(status().isForbidden());
        assertThat(customer.getFullName()).isEqualTo("Khách hàng");
    }

    @Test
    void bankInformationRequiresAllFieldsOrCanBeCleared() throws Exception {
        mvc.perform(post("/user/bank").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("bankName", "BIDV").param("accountName", "NGUYEN HUY TAN").param("accountNumber", "12345678"))
            .andExpect(status().is3xxRedirection());
        assertThat(customer.getBankAccountNumber()).isEqualTo("12345678");
        mvc.perform(post("/user/bank").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("bankName", "BIDV").param("accountName", "").param("accountNumber", ""))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("bankForm", "accountName", "accountNumber"));
        assertThat(customer.getBankAccountNumber()).isEqualTo("12345678");
        mvc.perform(post("/user/bank").with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(customer.getBankAccountNumber()).isNull();
    }

    @Test
    void passwordChangeChecksCurrentPasswordDifferenceAndUtf8Length() throws Exception {
        var original = customer.getPasswordHash();
        for (String[] values : new String[][]{{"wrong", "NewPassword123!"},
                {"Password123!", "Password123!"}, {"Password123!", "ă".repeat(40)}}) {
            mvc.perform(post("/user/password").with(user(customer.getUsername()).roles("USER")).with(csrf())
                .param("currentPassword", values[0]).param("newPassword", values[1]).param("confirmPassword", values[1]))
                .andExpect(status().isOk()).andExpect(model().hasErrors());
            assertThat(customer.getPasswordHash()).isEqualTo(original);
        }
        mvc.perform(post("/user/password").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("currentPassword", "Password123!").param("newPassword", "NewPassword123!")
            .param("confirmPassword", "Different123!")) .andExpect(model().attributeHasFieldErrors("passwordForm", "confirmPassword"));
        mvc.perform(post("/user/password").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("currentPassword", "Password123!").param("newPassword", "NewPassword123!")
            .param("confirmPassword", "NewPassword123!")) .andExpect(status().is3xxRedirection());
        assertThat(encoder.matches("NewPassword123!", customer.getPasswordHash())).isTrue();
    }

    @Test
    void addressesAlwaysKeepOneDefaultWhenEditedSwitchedOrDeleted() {
        profile.saveAddress(customer, null, form(false));
        var first = profile.addresses(customer).getFirst(); assertThat(first.isDefaultAddress()).isTrue();
        profile.saveAddress(customer, null, form(false));
        var second = profile.addresses(customer).get(1);
        profile.saveAddress(customer, first.getId(), form(false));
        assertThat(first.isDefaultAddress()).isTrue();
        profile.makeDefault(customer, second.getId());
        assertThat(profile.addresses(customer).stream().filter(a -> a.isDefaultAddress()).count()).isEqualTo(1);
        profile.deleteAddress(customer, second.getId());
        assertThat(profile.addresses(customer)).hasSize(1);
        assertThat(first.isDefaultAddress()).isTrue();
    }

    @Test
    void addressOwnershipIsCheckedForEveryEndpoint() throws Exception {
        profile.saveAddress(other, null, form(false));
        var foreign = profile.addresses(other).getFirst();
        mvc.perform(get("/user/profile").with(user(customer.getUsername()).roles("USER"))
            .param("editAddress", foreign.getId().toString())).andExpect(status().isNotFound());
        for (String action : new String[]{"default", "delete"}) {
            mvc.perform(post("/user/addresses/" + foreign.getId() + "/" + action)
                .with(user(customer.getUsername()).roles("USER")).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(post("/user/addresses/save").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("id", foreign.getId().toString()).param("receiverName", "Changed").param("phone", "0901234567")
            .param("province", "HCM").param("district", "Thủ Đức").param("ward", "Linh Chiểu").param("detail", "01 Võ Văn Ngân"))
            .andExpect(status().isNotFound());
        assertThat(addresses.findById(foreign.getId()).orElseThrow().getReceiverName()).isEqualTo("Nguyễn Huy Tân");
    }

    @Test
    void avatarUsesSharedStorageAndCanBeRemoved() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aG1cAAAAASUVORK5CYII=");
        mvc.perform(multipart("/user/avatar").file(new MockMultipartFile("avatar", "avatar.png", "image/png", png))
            .with(user(customer.getUsername()).roles("USER")).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(customer.getAvatarUrl()).startsWith("/uploads/avatars/");
        mvc.perform(post("/user/avatar/remove").with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(customer.getAvatarUrl()).isNull();
    }

    @Test
    void avatarRejectsUnsupportedMimeAndOversizedFiles() throws Exception {
        for (var file : new MockMultipartFile[]{new MockMultipartFile("avatar", "avatar.png", "application/pdf", new byte[]{1}),
                new MockMultipartFile("avatar", "avatar.png", "image/png", new byte[5 * 1024 * 1024 + 1])}) {
            mvc.perform(multipart("/user/avatar").file(file).with(user(customer.getUsername()).roles("USER")).with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeExists("avatarError"));
            assertThat(customer.getAvatarUrl()).isNull();
        }
    }

    @Test
    void guestsAndManagementRolesCannotModifyCustomerData() throws Exception {
        mvc.perform(get("/user/profile")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/user/profile").with(user("admin").roles("ADMIN")).with(csrf())
            .param("fullName", "Changed")) .andExpect(status().is3xxRedirection());
        assertThat(customer.getFullName()).isEqualTo("Khách hàng");
    }

    private AddressForm form(boolean makeDefault) {
        var form = new AddressForm(); form.setReceiverName("Nguyễn Huy Tân"); form.setPhone("0901234567");
        form.setProvince("HCM"); form.setDistrict("Thủ Đức"); form.setWard("Linh Chiểu");
        form.setDetail("01 Võ Văn Ngân"); form.setDefaultAddress(makeDefault); return form;
    }
}
