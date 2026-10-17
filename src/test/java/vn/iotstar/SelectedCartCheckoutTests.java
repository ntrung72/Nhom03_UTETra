package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SelectedCartCheckoutTests extends CheckoutTestFixtures {
    @Test
    void selectionCheckoutAndCancellationPreserveUnpurchasedVariant() throws Exception {
        var selected = add(tea, medium, 2); var left = add(tea, large, 1);
        var session = new MockHttpSession();
        mvc.perform(post("/cart/selection").session(session).with(user(customer.getUsername())).with(csrf())
            .param("itemIds", selected.getId().toString())).andExpect(redirectedUrl("/cart"));
        mvc.perform(post("/checkout").session(session).with(user(customer.getUsername())).with(csrf())
            .param("itemIds", selected.getId().toString()).param("addressId", address.getId().toString())
            .param("carrierId", carrier.getId().toString())).andExpect(redirectedUrl("/orders"));
        assertThat(session.getAttribute("cartSelection")).isNull();
        assertThat(session.getAttribute("cartSelectionOwner")).isNull();
        var order = orderRepository.findAll().getFirst();
        assertThat(order.getItems()).singleElement().satisfies(i -> assertThat(i.getQuantity()).isEqualTo(2));
        mvc.perform(post("/orders/" + order.getId() + "/cancel").with(user(customer.getUsername())).with(csrf()))
            .andExpect(redirectedUrl("/orders/" + order.getId()));
        assertThat(carts.get(customer).getItems()).containsExactly(left);
        assertThat(tea.getStock()).isEqualTo(10);
    }

    @Test
    void switchingAccountInSameSessionCannotReuseAnotherAccountsSelection() throws Exception {
        var line = add(tea, medium, 1); var session = new MockHttpSession();
        mvc.perform(post("/cart/selection").session(session).with(user(customer.getUsername())).with(csrf())
            .param("itemIds", line.getId().toString())).andExpect(redirectedUrl("/cart"));
        mvc.perform(get("/cart").session(session).with(user(other.getUsername())))
            .andExpect(status().isOk()).andExpect(model().attribute("selectedIds", java.util.List.of()));
        mvc.perform(post("/checkout").session(session).with(user(other.getUsername())).with(csrf())
            .param("itemIds", line.getId().toString()).param("addressId", address.getId().toString())
            .param("carrierId", carrier.getId().toString())).andExpect(status().isNotFound());
        assertThat(orderRepository.count()).isZero();
        assertThat(tea.getStock()).isEqualTo(10);
        assertThat(carts.get(customer).getItems()).containsExactly(line);
    }
}
