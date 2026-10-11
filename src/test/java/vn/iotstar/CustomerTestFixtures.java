package vn.iotstar;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

abstract class CustomerTestFixtures {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired ShopRepository shops;
    @Autowired CategoryRepository categories;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;
    User customer;
    User other;
    Product tea;

    @BeforeEach
    void fixtures() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = createUser(); other = createUser();
        var shop = new Shop(); shop.setOwner(other); shop.setName("UTETra test");
        shop.setSlug(UUID.randomUUID().toString()); shop.setStatus(ShopStatus.APPROVED); shops.save(shop);
        var category = new Category(); category.setName("Trà sữa");
        category.setSlug(UUID.randomUUID().toString()); categories.save(category);
        tea = new Product(); tea.setShop(shop); tea.setCategory(category); tea.setName("Trà sữa test");
        tea.setSlug("tra-sua-test"); tea.setSku(UUID.randomUUID().toString()); tea.setDescription("Trà tươi mỗi ngày");
        tea.setPrice(new BigDecimal("35000")); tea.setStock(10); products.saveAndFlush(tea);
    }

    User createUser() {
        String key = UUID.randomUUID().toString();
        var user = new User(); user.setUsername(key); user.setEmail(key + "@test.vn");
        user.setFullName("Khách hàng"); user.setEnabled(true); user.setPasswordHash(encoder.encode("Password123!"));
        return users.saveAndFlush(user);
    }
}
