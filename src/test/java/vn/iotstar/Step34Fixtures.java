package vn.iotstar;

import java.math.BigDecimal;
import java.util.UUID;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

/** Step 34 integration-test fixtures. No production or customer/vendor logic. */
final class Step34Fixtures {
    private Step34Fixtures() { }
    static String uid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    static User user(UserRepository users, Role role) {
        String key = uid();
        User u = new User();
        u.setUsername("s34" + key);
        u.setEmail("s34" + key + "@example.invalid");
        u.setFullName("Step 34 " + key);
        u.setPasswordHash("test-password-hash");
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }
    static Shop shop(UserRepository users, ShopRepository shops) {
        Shop s = new Shop();
        s.setOwner(user(users, Role.VENDOR));
        s.setName("Step34 Shop " + uid());
        s.setSlug("step34-shop-" + uid());
        s.setStatus(ShopStatus.APPROVED);
        s.setCommissionRate(BigDecimal.ZERO);
        return shops.saveAndFlush(s);
    }
    static ManagerShop assign(ManagerShopRepository repository, User admin, User manager, Shop shop) {
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        return repository.saveAndFlush(assignment);
    }
    static CustomerOrder order(OrderRepository orders, User buyer, Shop shop, OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setCode("S34" + uid());
        o.setUser(buyer);
        o.setShop(shop);
        o.setStatus(status);
        o.setReceiverName("Người nhận");
        o.setReceiverPhone("0900000000");
        o.setShippingAddress("123 Đường Test");
        o.setSubtotal(new BigDecimal("100000"));
        o.setTotalAmount(new BigDecimal("100000"));
        o.setProductDiscount(BigDecimal.ZERO);
        o.setVoucherDiscount(BigDecimal.ZERO);
        o.setShippingDiscount(BigDecimal.ZERO);
        o.setFinalShippingFee(BigDecimal.ZERO);
        return orders.saveAndFlush(o);
    }
}
