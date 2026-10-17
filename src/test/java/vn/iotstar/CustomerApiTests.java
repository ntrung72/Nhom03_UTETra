package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerApiTests extends CustomerTestFixtures {
    @Autowired JwtService jwt;
    @Autowired CustomUserDetailsService details;
    @Autowired CartRepository carts;

    @Test
    void bearerCanReadEmptyResourcesWithoutCreatingCart() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(details.loadUserByUsername(customer.getUsername()));
        for (String path : new String[]{"/api/orders", "/api/favorites"}) {
            mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(0));
        }
        mvc.perform(get("/api/cart").header("Authorization", bearer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.subtotal").value(0))
            .andExpect(jsonPath("$.totalQuantity").value(0));
        assertThat(carts.findByUserId(customer.getId())).isEmpty();
    }

    @Test
    void customerResourcesRequireBearerAuthentication() throws Exception {
        for (String path : new String[]{"/api/cart", "/api/favorites", "/api/orders"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void swaggerPublishesReadEndpointsAndConcreteAccountSchemas() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/products'].get").exists())
            .andExpect(jsonPath("$.paths['/api/orders'].get.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/favorites'].get.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/cart'].get.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.token").exists())
            .andExpect(jsonPath("$.components.schemas.MeResponse.properties.role").exists());
    }
}
