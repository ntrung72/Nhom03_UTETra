package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class PaymentManagementTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private ManagementService managementService;
    private MockMvc mockMvc;
    private User admin;
    private User manager;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = saveUser("payment-admin-" + suffix, Role.ADMIN);
        manager = saveUser("payment-manager-" + suffix, Role.MANAGER);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void adminCanSearchAndFilterPaymentTransactions() throws Exception {
        User customer = saveUser("payment-customer-" + suffix, Role.USER);
        Shop shop = saveShop(suffix);
        CustomerOrder successOrder = saveOrder(customer, shop, "UTE-PAY-SUCCESS-" + suffix, "120000");
        CustomerOrder failedOrder = saveOrder(customer, shop, "UTE-PAY-FAILED-" + suffix, "80000");
        saveTransaction(successOrder, "UTETRA-TXN-SUCCESS-" + suffix, "120000", PaymentTransactionStatus.SUCCESS);
        saveTransaction(failedOrder, "UTETRA-TXN-FAILED-" + suffix, "80000", PaymentTransactionStatus.FAILED);

        var result = managementService.payments(admin, "SUCCESS-" + suffix,
            PaymentTransactionStatus.SUCCESS, 0);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getOrder().getCode()).isEqualTo(successOrder.getCode());

        mockMvc.perform(get("/management/payments")
                .param("q", customer.getUsername()).param("status", "SUCCESS")
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("UTETRA-TXN-SUCCESS-" + suffix)))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("UTETRA-TXN-FAILED-" + suffix))));
    }

    @Test
    void managerCannotAccessSystemPaymentTransactions() throws Exception {
        assertThatThrownBy(() -> managementService.payments(manager, null, null, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Admin");

        mockMvc.perform(get("/management/payments").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
    }

    @Test
    void adminSeesReconciliationWarningForTwoSuccessfulPaymentsOfOneOrder() throws Exception {
        User customer = saveUser("payment-double-customer-" + suffix, Role.USER);
        CustomerOrder order = saveOrder(customer, saveShop(suffix), "UTE-PAY-DOUBLE-" + suffix, "50000");
        order.setPaymentStatus(PaymentStatus.PAID);
        saveTransaction(order, "UTETRA-TXN-DOUBLE-A-" + suffix, "50000", PaymentTransactionStatus.SUCCESS);
        saveTransaction(order, "UTETRA-TXN-DOUBLE-B-" + suffix, "50000", PaymentTransactionStatus.SUCCESS);

        mockMvc.perform(get("/management/payments")
                .param("q", order.getCode())
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Cần đối soát")));
    }

    private PaymentTransaction saveTransaction(CustomerOrder order, String code, String amount,
                                               PaymentTransactionStatus status) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setOrder(order);
        transaction.setTransactionCode(code);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setStatus(status);
        if (status == PaymentTransactionStatus.SUCCESS) transaction.setPaidAt(LocalDateTime.now());
        return paymentTransactionRepository.save(transaction);
    }

    private CustomerOrder saveOrder(User customer, Shop shop, String code, String amount) {
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setUser(customer);
        order.setShop(shop);
        order.setPaymentMethod(PaymentMethod.VNPAY);
        order.setReceiverName(customer.getFullName());
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Võ Văn Ngân, Thủ Đức");
        order.setSubtotal(new BigDecimal(amount));
        order.setTotalAmount(new BigDecimal(amount));
        return orderRepository.save(order);
    }

    private Shop saveShop(String value) {
        Shop shop = new Shop();
        shop.setOwner(saveUser("payment-vendor-" + value, Role.VENDOR));
        shop.setName("UTETra Payment " + value);
        shop.setSlug("utetra-payment-" + value);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return userRepository.save(account);
    }
}
