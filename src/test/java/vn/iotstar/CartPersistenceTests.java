package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.repository.*;

@SpringBootTest
@ActiveProfiles("test")
class CartPersistenceTests extends CustomerTestFixtures {
    @Autowired CartRepository carts;
    @Autowired ProductSizeRepository sizes;
    @Autowired TransactionTemplate transactions;

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(status -> {
            carts.findByUserId(customer.getId()).ifPresent(carts::delete);
            carts.flush(); sizes.deleteByProductId(tea.getId()); sizes.flush();
            products.deleteById(tea.getId()); products.flush();
            shops.deleteById(tea.getShop().getId()); shops.flush();
            categories.deleteById(tea.getCategory().getId()); categories.flush();
            users.deleteById(customer.getId()); users.deleteById(other.getId());
        });
    }

    @Test
    void separateRequestsPersistSnapshotsRepriceAndRemoveWithoutStaleLines() throws Exception {
        var size = new ProductSize(); size.setProduct(tea); size.setCode("M");
        size.setExtraPrice(new BigDecimal("2000")); sizes.saveAndFlush(size);
        mvc.perform(post("/cart/add/" + tea.getId()).with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("sizeId", size.getId().toString()).param("quantity", "2")
            .param("unitPrice", "1").param("optionPrice", "0")) .andExpect(redirectedUrl("/cart"));
        var saved = carts.findByUserId(customer.getId()).orElseThrow();
        assertThat(saved.getItems()).hasSize(1);
        var item = saved.getItems().getFirst(); assertThat(item.getUnitPrice()).isEqualByComparingTo("37000");
        transactions.executeWithoutResult(status -> {
            var product = products.findById(tea.getId()).orElseThrow();
            product.setPrice(new BigDecimal("40000"));
        });
        var html = mvc.perform(get("/cart").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Giỏ hàng đã cập nhật theo giá hiện tại.");
        assertThat(carts.findByUserId(customer.getId()).orElseThrow().getItems().getFirst().getUnitPrice())
            .isEqualByComparingTo("42000");
        mvc.perform(post("/cart/items/" + item.getId() + "/delete").with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(redirectedUrl("/cart"));
        assertThat(carts.findByUserId(customer.getId()).orElseThrow().getItems()).isEmpty();
        mvc.perform(get("/cart").with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
    }
}
