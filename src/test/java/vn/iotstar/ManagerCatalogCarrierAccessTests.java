package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.UserRepository;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ManagerCatalogCarrierAccessTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CarrierRepository carrierRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User manager = new User();
        manager.setUsername("permission-manager");
        manager.setEmail("permission-manager@utetra.test");
        manager.setPasswordHash("encoded-password");
        manager.setFullName("Permission Manager");
        manager.setRole(Role.MANAGER);
        manager.setEnabled(true);
        userRepository.save(manager);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(username = "permission-manager", roles = "MANAGER")
    void managerCanOpenAndManageSharedCategoriesAndCarriers() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String categoryName = "Danh mục manager " + suffix;
        String carrierName = "Vận chuyển manager " + suffix;

        mockMvc.perform(get("/management/categories")).andExpect(status().isOk());
        mockMvc.perform(get("/management/categories/new")).andExpect(status().isOk());
        mockMvc.perform(get("/management/carriers")).andExpect(status().isOk());
        mockMvc.perform(get("/management/carriers/new")).andExpect(status().isOk());

        mockMvc.perform(post("/management/categories/save").with(csrf())
                .param("name", categoryName).param("description", "Danh mục dùng chung").param("active", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/management/categories"));
        var category = categoryRepository.findAll().stream()
            .filter(item -> categoryName.equals(item.getName())).findFirst().orElseThrow();
        mockMvc.perform(get("/management/categories/{id}/edit", category.getId())).andExpect(status().isOk());
        mockMvc.perform(post("/management/categories/{id}/toggle", category.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertFalse(categoryRepository.findById(category.getId()).orElseThrow().isActive());

        mockMvc.perform(post("/management/carriers/save").with(csrf())
                .param("name", carrierName).param("shippingFee", "12000").param("active", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/management/carriers"));
        var carrier = carrierRepository.findAll().stream()
            .filter(item -> carrierName.equals(item.getName())).findFirst().orElseThrow();
        mockMvc.perform(get("/management/carriers/{id}/edit", carrier.getId())).andExpect(status().isOk());
        mockMvc.perform(post("/management/carriers/{id}/toggle", carrier.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertFalse(carrierRepository.findById(carrier.getId()).orElseThrow().isActive());
    }
}
