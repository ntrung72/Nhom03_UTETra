package vn.iotstar;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;
import vn.iotstar.service.EngagementService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerJwtApiTests extends CheckoutTestFixtures {
    @Autowired JwtService jwt;
    @Autowired CustomUserDetailsService details;
    @Autowired EngagementService engagement;
    @Autowired ObjectMapper mapper;

    String bearer(User account) {
        return "Bearer " + jwt.generateToken(details.loadUserByUsername(account.getUsername()));
    }

    @Test
    void loginTokenReadsAccountAndDoesNotExposeCredentialsOrRelations() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(mapper.writeValueAsString(java.util.Map.of("username", customer.getEmail(), "password", "Password123!"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.expiresInMinutes").value(jwt.getExpirationMinutes()))
            .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).get("token").asText();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(customer.getId()))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.addresses").doesNotExist());
    }

    @Test
    void forgedUserIdCannotReadAnotherBuyersCartFavoritesOrOrders() throws Exception {
        var line = add(tea, medium, 2);
        engagement.toggleFavorite(customer, tea.getId());
        var order = orders.checkout(customer, form(line.getId())).getFirst();
        add(tea, large, 1);
        for (String path : new String[]{"/api/orders", "/api/favorites"}) {
            mvc.perform(get(path).header("Authorization", bearer(other)).param("userId", customer.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        }
        mvc.perform(get("/api/cart").header("Authorization", bearer(other)).param("userId", customer.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/orders").header("Authorization", bearer(customer)).param("userId", other.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value(order.getCode()))
            .andExpect(jsonPath("$.content[0].user").doesNotExist()).andExpect(jsonPath("$.content[0].items").doesNotExist());
        mvc.perform(get("/api/favorites").header("Authorization", bearer(customer)))
            .andExpect(jsonPath("$.content[0].productId").value(tea.getId()))
            .andExpect(jsonPath("$.content[0].product").doesNotExist());
        mvc.perform(get("/api/cart").header("Authorization", bearer(customer)))
            .andExpect(jsonPath("$.items[0].size").value("L")).andExpect(jsonPath("$.subtotal").value(42000))
            .andExpect(jsonPath("$.items[0].cart").doesNotExist()).andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void customerAndVendorTokensWorkButAllStaffRolesAreDenied() throws Exception {
        for (Role role : Role.values()) {
            customer.setRole(role); users.saveAndFlush(customer);
            String token = bearer(customer);
            for (String path : new String[]{"/api/cart", "/api/favorites", "/api/orders"}) {
                mvc.perform(get(path).header("Authorization", token))
                    .andExpect(role == Role.USER || role == Role.VENDOR ? status().isOk() : status().isForbidden());
            }
        }
    }

    @Test
    void tokensUseCurrentAccountStateAndRoleRatherThanOldClaims() throws Exception {
        String token = bearer(customer);
        customer.setRole(Role.ADMIN); users.saveAndFlush(customer);
        mvc.perform(get("/api/cart").header("Authorization", token)).andExpect(status().isForbidden());
        customer.setRole(Role.USER); customer.setLocked(true); users.saveAndFlush(customer);
        for (String path : new String[]{"/api/cart", "/api/favorites", "/api/orders"}) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isUnauthorized());
        }
        customer.setLocked(false); customer.setEnabled(false); users.saveAndFlush(customer);
        mvc.perform(get("/api/orders").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void webSessionCannotAuthenticatePrivateApiAndBearerDoesNotCreateSession() throws Exception {
        var login = mvc.perform(post("/login").with(csrf()).param("username", customer.getUsername())
            .param("password", "Password123!")).andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        for (String path : new String[]{"/api/cart", "/api/favorites", "/api/orders"}) {
            mvc.perform(get(path).session(session)).andExpect(status().isUnauthorized());
            var result = mvc.perform(get(path).header("Authorization", bearer(customer)))
                .andExpect(status().isOk()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    @Test
    void orderApiHasStableFifteenItemPagesStatusFilterAndParameterValidation() throws Exception {
        for (int i = 0; i < 17; i++) {
            var order = new CustomerOrder(); order.setUser(customer); order.setShop(tea.getShop());
            order.setSubtotal(BigDecimal.ZERO); order.setTotalAmount(BigDecimal.ZERO);
            order.setProductDiscount(BigDecimal.ZERO); order.setVoucherDiscount(BigDecimal.ZERO);
            order.setShippingDiscount(BigDecimal.ZERO); order.setFinalShippingFee(BigDecimal.ZERO);
            order.setCode("API-" + java.util.UUID.randomUUID().toString().substring(0, 24)); order.setReceiverName("Tân");
            order.setReceiverPhone("0901234567"); order.setShippingAddress("Thủ Đức");
            order.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
            order.setStatus(i < 2 ? OrderStatus.CANCELLED : OrderStatus.NEW); orderRepository.save(order);
        }
        orderRepository.flush();
        String token = bearer(customer);
        var expected = orders.forUser(customer, null, 0).getContent();
        mvc.perform(get("/api/orders").header("Authorization", token).param("page", "-1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.number").value(0))
            .andExpect(jsonPath("$.size").value(15)).andExpect(jsonPath("$.totalElements").value(17))
            .andExpect(jsonPath("$.content[0].id").value(expected.getFirst().getId()));
        mvc.perform(get("/api/orders").header("Authorization", token).param("page", "1"))
            .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/orders").header("Authorization", token).param("status", "CANCELLED"))
            .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/orders").header("Authorization", token).param("page", "999"))
            .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/orders").header("Authorization", token).param("status", "BOGUS"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/favorites").header("Authorization", token).param("page", "NaN"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cartReadPreservesPriceSnapshotAndFavoriteReadHidesUnavailableProducts() throws Exception {
        var line = add(tea, medium, 2); engagement.toggleFavorite(customer, tea.getId());
        tea.setPrice(new BigDecimal("40000")); tea.setStatus(ProductStatus.HIDDEN); products.saveAndFlush(tea);
        mvc.perform(get("/api/cart").header("Authorization", bearer(customer)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subtotal").value(70000));
        assertThat(line.getUnitPrice()).isEqualByComparingTo("35000");
        assertThat(tea.getStock()).isEqualTo(10);
        mvc.perform(get("/api/favorites").header("Authorization", bearer(customer)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }
}
