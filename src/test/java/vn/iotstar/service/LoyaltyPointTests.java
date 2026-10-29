package vn.iotstar.service;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.LoyaltyPointType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.LoyaltyPointTransactionRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class LoyaltyPointTests {
    @Autowired private OrderService orderService;
    @Autowired private LoyaltyPointService loyaltyPointService;
    @Autowired private LoyaltyPointTransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private WebApplicationContext context;

    @Test
    void codDeliveredAwardsNetMerchandiseOnlyAndRefundReversesExactlyOnce() {
        User customer = saveUser("loyalty-customer", Role.USER);
        CustomerOrder order = order(customer, PaymentMethod.COD, PaymentStatus.UNPAID,
            "67000", "52000", "15000");

        assertEquals(0, loyaltyPointService.balance(customer));
        order.setCodCollectedAt(LocalDateTime.now());
        orderService.applyStatus(order, OrderStatus.DELIVERED, customer, "Giao thành công");
        assertEquals(PaymentStatus.PAID, order.getPaymentStatus());
        assertEquals(5, loyaltyPointService.balance(customer));
        assertEquals(1, transactionRepository.findByUserIdOrderByCreatedAtDescIdDesc(customer.getId(),
            org.springframework.data.domain.PageRequest.of(0, 20)).getTotalElements());
        var earned = transactionRepository.findByOrderIdAndType(order.getId(), LoyaltyPointType.EARN).orElseThrow();
        assertEquals(new BigDecimal("52000"), earned.getEligibleAmount());
        assertEquals(5, earned.getPointsChange());

        orderService.applyStatus(order, OrderStatus.DELIVERED, customer, "Lặp callback");
        assertEquals(5, loyaltyPointService.balance(customer));

        orderService.applyStatus(order, OrderStatus.RETURN_REQUESTED, customer, "Yêu cầu trả hàng");
        assertEquals(5, loyaltyPointService.balance(customer));
        orderService.applyStatus(order, OrderStatus.RETURNED_REFUNDED, customer, "Hoàn toàn bộ đơn");
        assertEquals(0, loyaltyPointService.balance(customer));
        assertEquals(-5, transactionRepository.findByOrderIdAndType(order.getId(),
            LoyaltyPointType.REFUND_REVERSAL).orElseThrow().getPointsChange());
        orderService.applyStatus(order, OrderStatus.RETURNED_REFUNDED, customer, "Lặp xác nhận");
        assertEquals(0, loyaltyPointService.balance(customer));
        assertEquals(2, loyaltyPointService.history(customer, 0).getTotalElements());
    }

    @Test
    void unpaidOrTooSmallOrdersDoNotEarnAndUsersCannotSeeEachOthersPoints() {
        User customer = saveUser("loyalty-a", Role.USER);
        User other = saveUser("loyalty-b", Role.USER);
        CustomerOrder unpaid = order(customer, PaymentMethod.BANK_TRANSFER, PaymentStatus.UNPAID,
            "30000", "30000", "0");
        orderService.applyStatus(unpaid, OrderStatus.DELIVERED, customer, "Không có thanh toán");
        assertEquals(0, loyaltyPointService.balance(customer));

        CustomerOrder tiny = order(customer, PaymentMethod.BANK_TRANSFER, PaymentStatus.PAID,
            "25000", "9000", "16000");
        orderService.applyStatus(tiny, OrderStatus.DELIVERED, customer, "Đã thanh toán");
        assertEquals(0, loyaltyPointService.balance(customer));

        CustomerOrder paid = order(customer, PaymentMethod.BANK_TRANSFER, PaymentStatus.PAID,
            "40000", "30000", "10000");
        orderService.applyStatus(paid, OrderStatus.DELIVERED, customer, "Đã thanh toán");
        assertEquals(3, loyaltyPointService.balance(customer));
        assertEquals(0, loyaltyPointService.balance(other));
        assertTrue(loyaltyPointService.history(other, 0).isEmpty());
    }

    @Test
    void customerCanOpenOwnLoyaltyHistoryPage() throws Exception {
        User customer = saveUser("loyalty-page", Role.USER);
        CustomerOrder order = order(customer, PaymentMethod.COD, PaymentStatus.UNPAID,
            "30000", "30000", "0");
        order.setCodCollectedAt(LocalDateTime.now());
        orderService.applyStatus(order, OrderStatus.DELIVERED, customer, "Giao thành công");

        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
            .perform(get("/user/loyalty").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("account-hero")))
            .andExpect(content().string(containsString("account-nav")));
    }

    private User saveUser(String prefix, Role role) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(name);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private CustomerOrder order(User customer, PaymentMethod method, PaymentStatus payment,
                                String total, String merchandise, String shipping) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Shop shop = new Shop();
        shop.setOwner(saveUser("loyalty-vendor", Role.VENDOR));
        shop.setName("Shop " + suffix);
        shop.setSlug("loyalty-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shopRepository.save(shop);

        CustomerOrder order = new CustomerOrder();
        order.setCode("LOY-" + suffix);
        order.setUser(customer);
        order.setShop(shop);
        order.setStatus(OrderStatus.SHIPPING);
        order.setPaymentMethod(method);
        order.setPaymentStatus(payment);
        order.setReceiverName(customer.getFullName());
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Vo Van Ngan, Thu Duc");
        order.setSubtotal(new BigDecimal(merchandise));
        order.setCommissionBase(new BigDecimal(merchandise));
        order.setShippingFee(new BigDecimal(shipping));
        order.setTotalAmount(new BigDecimal(total));
        return orderRepository.save(order);
    }
}
