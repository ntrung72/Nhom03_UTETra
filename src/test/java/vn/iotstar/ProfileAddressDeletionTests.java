package vn.iotstar;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.UserRepository;

@ActiveProfiles("test")
@SpringBootTest
class ProfileAddressDeletionTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private AddressRepository addressRepository;

    @Test
    void deletingDefaultAddressRemovesItAndPromotesAnotherAddress() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = new User();
        customer.setUsername("address-delete-" + suffix);
        customer.setEmail("address-delete-" + suffix + "@utetra.test");
        customer.setPasswordHash("encoded-password");
        customer.setFullName("Address Test");
        customer.setRole(Role.USER);
        customer.setEnabled(true);
        customer = userRepository.save(customer);
        Address first = addressRepository.save(address(customer, "Nhà cần xóa " + suffix, true));
        Address second = addressRepository.save(address(customer, "Nhà giữ lại " + suffix, false));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var customerRequest = user(customer.getUsername()).roles("USER");

        mvc.perform(post("/user/addresses/{id}/delete", first.getId()).with(customerRequest).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile?tab=addresses"));
        mvc.perform(get("/user/profile").param("tab", "addresses").with(customerRequest))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString(first.getDetail()))))
            .andExpect(content().string(containsString(second.getDetail())));
        assertFalse(addressRepository.existsById(first.getId()));
        assertTrue(addressRepository.findById(second.getId()).orElseThrow().isDefaultAddress());
    }

    private Address address(User user, String detail, boolean isDefault) {
        Address address = new Address();
        address.setUser(user);
        address.setReceiverName("Address Test");
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("TP. Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail(detail);
        address.setDefaultAddress(isDefault);
        return address;
    }
}
