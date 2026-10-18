package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.Topping;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.ProductOptionService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ToppingManagementTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ToppingRepository toppingRepository;
    @Autowired private ManagementService managementService;
    @Autowired private ProductOptionService productOptionService;
    @Autowired private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;
    private String suffix;
    private User manager;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        manager = saveUser("topping-manager-" + suffix, Role.MANAGER);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void managerCanOpenCreateEditAndToggleToppings() throws Exception {
        mockMvc.perform(get("/management/toppings").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());

        String name = "Thạch đào " + suffix;
        mockMvc.perform(post("/management/toppings/save")
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf())
                .param("name", name).param("price", "9000").param("active", "true"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management/toppings"));
        Topping topping = toppingRepository.findByNameIgnoreCase(name).orElseThrow();
        assertThat(topping.getPrice()).isEqualByComparingTo("9000");
        assertThat(topping.isActive()).isTrue();

        mockMvc.perform(post("/management/toppings/save")
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf())
                .param("id", topping.getId().toString()).param("name", name)
                .param("price", "12000").param("active", "true"))
            .andExpect(status().is3xxRedirection());
        assertThat(toppingRepository.findById(topping.getId()).orElseThrow().getPrice())
            .isEqualByComparingTo("12000");

        mockMvc.perform(post("/management/toppings/{id}/toggle", topping.getId())
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(toppingRepository.findById(topping.getId()).orElseThrow().isActive()).isFalse();
        assertThat(productOptionService.availableToppings()).noneMatch(item -> item.getId().equals(topping.getId()));
    }

    @Test
    void vendorCannotEnterToppingManagement() throws Exception {
        User vendor = saveUser("topping-vendor-" + suffix, Role.VENDOR);
        mockMvc.perform(get("/management/toppings").with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/vendor"));
    }

    @Test
    void validatesDuplicatesDeletesUnusedAndPreservesManagedDefaultValues() {
        String name = "Pudding quản lý " + suffix;
        managementService.saveTopping(null, form(name, "11000", true));
        assertThatThrownBy(() -> managementService.saveTopping(null, form(name.toUpperCase(), "13000", true)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đã tồn tại");

        Topping unused = toppingRepository.findByNameIgnoreCase(name).orElseThrow();
        managementService.deleteTopping(unused.getId());
        assertThat(toppingRepository.findById(unused.getId())).isEmpty();

        Topping kemCheese = new Topping();
        kemCheese.setName("Kem cheese");
        kemCheese.setPrice(new BigDecimal("19000"));
        kemCheese.setActive(false);
        toppingRepository.save(kemCheese);
        productOptionService.configureAllProducts();
        Topping preserved = toppingRepository.findByNameIgnoreCase("Kem cheese").orElseThrow();
        assertThat(preserved.getPrice()).isEqualByComparingTo("19000");
        assertThat(preserved.isActive()).isFalse();
    }

    private ToppingForm form(String name, String price, boolean active) {
        ToppingForm form = new ToppingForm();
        form.setName(name);
        form.setPrice(new BigDecimal(price));
        form.setActive(active);
        return form;
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash(passwordEncoder.encode("Password@123"));
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return userRepository.save(account);
    }
}
