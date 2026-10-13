package vn.iotstar;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;

abstract class CheckoutTestFixtures extends CustomerTestFixtures {
    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItems;
    @Autowired OrderStatusHistoryRepository histories;
    @Autowired CartItemRepository cartItems;
    @Autowired ProductSizeRepository sizes;
    @Autowired AddressRepository addresses;
    @Autowired CarrierRepository carriers;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherUsageRepository usages;
    @Autowired PromotionRepository promotions;
    Address address;
    Carrier carrier;
    ProductSize medium;
    ProductSize large;

    @BeforeEach
    void checkoutFixtures() {
        address = new Address(); address.setUser(customer); address.setReceiverName("Tân");
        address.setPhone("0901234567"); address.setProvince("TP HCM"); address.setDistrict("Thủ Đức");
        address.setWard("Linh Trung"); address.setDetail("1 Võ Văn Ngân"); address.setDefaultAddress(true);
        addresses.saveAndFlush(address);
        carrier = new Carrier(); carrier.setName(UUID.randomUUID().toString());
        carrier.setShippingFee(new BigDecimal("20000")); carriers.saveAndFlush(carrier);
        medium = size(tea, "M", "0"); large = size(tea, "L", "7000");
    }

    ProductSize size(Product product, String code, String extra) {
        var size = new ProductSize(); size.setProduct(product); size.setCode(code);
        size.setExtraPrice(new BigDecimal(extra)); return sizes.saveAndFlush(size);
    }

    CartItem add(Product product, ProductSize size, int quantity) {
        carts.add(customer, product.getId(), quantity, size.getId(), null, null, List.of());
        return carts.get(customer).getItems().stream().filter(i -> i.getProduct().getId().equals(product.getId())
            && i.getSizeId().equals(size.getId())).findFirst().orElseThrow();
    }

    CheckoutForm form(Long... ids) {
        var form = new CheckoutForm(); form.setAddressId(address.getId()); form.setCarrierId(carrier.getId());
        form.setItemIds(List.of(ids)); return form;
    }

    Product secondShopProduct() {
        var shop = new Shop(); shop.setOwner(createUser()); shop.setName("Shop thứ hai");
        shop.setSlug(UUID.randomUUID().toString()); shop.setStatus(ShopStatus.APPROVED); shops.saveAndFlush(shop);
        var product = new Product(); product.setShop(shop); product.setCategory(tea.getCategory());
        product.setName("Trà đào"); product.setDescription("Trà đào tươi"); product.setSlug("tra-dao");
        product.setSku(UUID.randomUUID().toString()); product.setPrice(new BigDecimal("15000")); product.setStock(20);
        return products.saveAndFlush(product);
    }

    Voucher voucher(VoucherType type, String value) {
        var v = new Voucher(); v.setCode(UUID.randomUUID().toString()); v.setName("Ưu đãi kiểm thử");
        v.setType(type); v.setDiscountValue(new BigDecimal(value)); v.setQuantity(3);
        v.setStartsAt(LocalDateTime.now().minusDays(1)); v.setEndsAt(LocalDateTime.now().plusDays(1));
        return vouchers.saveAndFlush(v);
    }

    Promotion promotion(PromotionType type, String value) {
        var p = new Promotion(); p.setName("Ưu đãi shop"); p.setShop(tea.getShop()); p.setType(type);
        p.setDiscountValue(new BigDecimal(value)); p.setStartsAt(LocalDateTime.now().minusDays(1));
        p.setEndsAt(LocalDateTime.now().plusDays(1)); return promotions.saveAndFlush(p);
    }
}
