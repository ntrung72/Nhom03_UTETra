package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.BankTransferService;
import vn.iotstar.service.OrderService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BankTransferPaymentTests {
    @Autowired private BankTransferService bankTransfers;
    @Autowired private OrderService orderService;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private WebApplicationContext context;

    @Test
    void checkoutPageRendersBothOrderQrs() throws Exception {
        User buyer = user("bank-page-buyer", Role.USER);
        Shop shop = shop(user("bank-page-vendor", Role.VENDOR), "bank-page-shop");
        String checkout = "CHK-BANK-PAGE";
        CustomerOrder first = order(buyer, shop, "UTEBANKPAGE01", "42000", checkout);
        CustomerOrder second = order(buyer, shop, "UTEBANKPAGE02", "58000", checkout);
        createLegacyPayments(first, second);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(get("/bank-transfer/checkout/" + checkout).with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(buyer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(first.getCode())))
            .andExpect(content().string(containsString(second.getCode())))
            .andExpect(content().string(containsString("6351077536")));
        mvc.perform(get("/bank-transfer/" + first.getCode() + "/qr")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(buyer.getUsername()).roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    void createsSeparateQrPerOrderAndAdminConfirmsOnlyMatchingTransfer() throws Exception {
        User buyer = user("bank-qr-buyer", Role.USER);
        User admin = user("bank-qr-admin", Role.ADMIN);
        User vendor = user("bank-qr-vendor", Role.VENDOR);
        Shop firstShop = shop(vendor, "bank-qr-shop-one");
        Shop secondShop = shop(user("bank-qr-other-vendor", Role.VENDOR), "bank-qr-shop-two");
        String checkout = "CHK-BANK-QR-TEST";
        CustomerOrder first = order(buyer, firstShop, "UTEBANKQR001", "40000", checkout);
        CustomerOrder second = order(buyer, secondShop, "UTEBANKQR002", "65000", checkout);

        createLegacyPayments(first, second);
        var checkoutDetails = bankTransfers.checkoutDetails(buyer, checkout);
        assertThat(checkoutDetails).extracting(BankTransferService.BankTransferDetails::reference)
            .containsExactly(first.getCode(), second.getCode());
        assertThat(checkoutDetails).extracting(BankTransferService.BankTransferDetails::total)
            .containsExactly(new BigDecimal("40000"), new BigDecimal("65000"));
        assertThat(first.getDisplayStatusLabel()).isEqualTo("Chờ xác nhận thanh toán");
        assertThatThrownBy(() -> bankTransfers.details(vendor, first.getCode()))
            .isInstanceOf(IllegalArgumentException.class);

        String firstQr = decode(bankTransfers.qrImage(buyer, first.getCode()));
        String secondQr = decode(bankTransfers.qrImage(buyer, second.getCode()));
        assertThat(firstQr).contains("970418", "6351077536", "TRAN NAM TRUNG", "540540000", first.getCode());
        assertThat(secondQr).contains("540565000", second.getCode());
        assertThat(firstQr).isNotEqualTo(secondQr);

        bankTransfers.markSent(buyer, first.getCode());
        assertThat(payments.findByTransactionCodeOrderByIdAsc(first.getCode()).getFirst().getStatus())
            .isEqualTo(PaymentTransactionStatus.AWAITING_REVIEW);
        assertThat(first.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThatThrownBy(() -> orderService.vendorUpdate(vendor, first.getId(), OrderStatus.CONFIRMED))
            .isInstanceOf(IllegalArgumentException.class);
        Long firstPaymentId = payments.findByTransactionCodeOrderByIdAsc(first.getCode()).getFirst().getId();
        Long secondPaymentId = payments.findByTransactionCodeOrderByIdAsc(second.getCode()).getFirst().getId();
        assertThatThrownBy(() -> bankTransfers.confirm(vendor, firstPaymentId, "BANKREF001", new BigDecimal("40000")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bankTransfers.confirm(admin, firstPaymentId, "BANKREF001", new BigDecimal("39999")))
            .isInstanceOf(IllegalArgumentException.class);

        bankTransfers.confirm(admin, firstPaymentId, "BANKREF001", new BigDecimal("40000"));
        assertThat(first.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(second.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThatThrownBy(() -> bankTransfers.confirm(admin, secondPaymentId, "BANKREF001", new BigDecimal("65000")))
            .isInstanceOf(IllegalArgumentException.class);
        bankTransfers.confirm(admin, secondPaymentId, "BANKREF002", new BigDecimal("65000"));
        assertThat(second.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThatThrownBy(() -> bankTransfers.confirm(admin, firstPaymentId, "BANKREF001", new BigDecimal("40000")))
            .isInstanceOf(IllegalArgumentException.class);

        CustomerOrder unreceived = order(buyer, firstShop, "UTEBANKQR003", "37000", "CHK-BANK-UNRECEIVED");
        createLegacyPayments(unreceived);
        Long unreceivedPaymentId = payments.findByTransactionCodeOrderByIdAsc(unreceived.getCode()).getFirst().getId();
        bankTransfers.markSent(buyer, unreceived.getCode());
        assertThatThrownBy(() -> bankTransfers.cancelUnreceived(vendor, unreceivedPaymentId))
            .isInstanceOf(IllegalArgumentException.class);
        bankTransfers.cancelUnreceived(admin, unreceivedPaymentId);
        assertThat(unreceived.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payments.findById(unreceivedPaymentId).orElseThrow().getStatus())
            .isEqualTo(PaymentTransactionStatus.CANCELLED);
        assertThatThrownBy(() -> bankTransfers.confirm(admin, unreceivedPaymentId, "BANKREF003", new BigDecimal("37000")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private String decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        return new MultiFormatReader().decode(new BinaryBitmap(
            new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)))).getText();
    }

    private void createLegacyPayments(CustomerOrder... orders) {
        for (CustomerOrder order : orders) {
            PaymentTransaction payment = new PaymentTransaction();
            payment.setOrder(order);
            payment.setTransactionCode(order.getCode());
            payment.setAmount(order.getTotalAmount());
            payments.save(payment);
        }
    }

    private User user(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(name);
        user.setRole(role);
        user.setEnabled(true);
        return users.save(user);
    }

    private Shop shop(User owner, String slug) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shops.save(shop);
    }

    private CustomerOrder order(User buyer, Shop shop, String code, String amount, String checkout) {
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setCheckoutReference(checkout);
        order.setUser(buyer);
        order.setShop(shop);
        order.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        order.setReceiverName(buyer.getFullName());
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Võ Văn Ngân, Thủ Đức");
        order.setSubtotal(new BigDecimal(amount));
        order.setTotalAmount(new BigDecimal(amount));
        return orders.save(order);
    }
}
