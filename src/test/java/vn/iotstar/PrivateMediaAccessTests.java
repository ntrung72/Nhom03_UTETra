package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.entity.DomainEnums.ReturnReason;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.ReturnRequestMedia;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ReturnRequestMediaRepository;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.MediaStorageService;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.upload-dir=target/private-media-test-public",
    "app.private-upload-dir=target/private-media-test-private",
    "cloudinary.cloud-name=", "cloudinary.api-key=", "cloudinary.api-secret="
})
@SpringBootTest
@Transactional
class PrivateMediaAccessTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ManagerShopRepository managerShopRepository;
    @Autowired private ReturnRequestRepository returnRequestRepository;
    @Autowired private ReturnRequestMediaRepository returnRequestMediaRepository;
    @Autowired private MediaStorageService mediaStorageService;

    @Test
    void onlyOrderParticipantsAndAuthorizedManagersCanReadProofs() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("proof-customer-" + suffix, Role.USER);
        User unrelatedCustomer = saveUser("proof-unrelated-" + suffix, Role.USER);
        User vendor = saveUser("proof-vendor-" + suffix, Role.VENDOR);
        User unrelatedVendor = saveUser("proof-other-vendor-" + suffix, Role.VENDOR);
        User shipper = saveUser("proof-shipper-" + suffix, Role.SHIPPER);
        User unrelatedShipper = saveUser("proof-other-shipper-" + suffix, Role.SHIPPER);
        User manager = saveUser("proof-manager-" + suffix, Role.MANAGER);
        User unrelatedManager = saveUser("proof-other-manager-" + suffix, Role.MANAGER);
        User admin = saveUser("proof-admin-" + suffix, Role.ADMIN);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("Private media test " + suffix);
        shop.setSlug("private-media-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        managerShopRepository.save(assignment);

        CustomerOrder order = new CustomerOrder();
        order.setCode("PROOF-" + suffix);
        order.setUser(customer);
        order.setShop(shop);
        order.setShipper(shipper);
        order.setReceiverName("Test Customer");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Vo Van Ngan");
        order.setSubtotal(new BigDecimal("30000"));
        order.setTotalAmount(new BigDecimal("30000"));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setDeliveryProofUrl(mediaStorageService.upload(image("delivery.png"), "delivery-proofs"));
        order = orderRepository.save(order);
        assertTrue(order.getDeliveryProofUrl().startsWith("private:delivery-proofs/"));

        ReturnRequest request = new ReturnRequest();
        request.setOrder(order);
        request.setUser(customer);
        request.setShop(shop);
        request.setReason(ReturnReason.OTHER);
        request.setDescription("Test return evidence");
        request.setRefundAmount(new BigDecimal("30000"));
        request = returnRequestRepository.save(request);
        ReturnRequestMedia evidence = new ReturnRequestMedia();
        evidence.setReturnRequest(request);
        evidence.setType(MediaType.IMAGE);
        evidence.setUrl(mediaStorageService.upload(image("return.png"), "returns"));
        evidence = returnRequestMediaRepository.save(evidence);
        assertTrue(evidence.getUrl().startsWith("private:returns/"));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String proofPath = "/private-media/orders/" + order.getId() + "/delivery-proof";
        String returnPath = "/private-media/returns/" + evidence.getId();
        for (String path : new String[] {proofPath, returnPath}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (User allowed : new User[] {customer, vendor, shipper, manager, admin}) {
                mvc.perform(get(path).with(user(allowed.getUsername()).roles(allowed.getRole().name())))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Type", "image/png"));
            }
            for (User denied : new User[] {unrelatedCustomer, unrelatedVendor, unrelatedShipper, unrelatedManager}) {
                mvc.perform(get(path).with(user(denied.getUsername()).roles(denied.getRole().name())))
                    .andExpect(status().isNotFound());
            }
        }

        for (String path : new String[] {
            order.getDeliveryProofUrl().replace("private:", "/uploads/"),
            evidence.getUrl().replace("private:", "/uploads/")
        }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user(customer.getUsername()).roles("USER")))
                .andExpect(status().isForbidden());
        }
        String evidenceReference = evidence.getUrl();
        assertThrows(IllegalArgumentException.class,
            () -> mediaStorageService.readPrivate(evidenceReference, "delivery-proofs"));

        String publicImage = mediaStorageService.upload(image("product.png"), "products");
        assertTrue(publicImage.startsWith("/uploads/products/"));
        mvc.perform(get(publicImage)).andExpect(status().isOk());
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private MockMultipartFile image(String filename) throws Exception {
        var buffered = new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(buffered, "png", out);
        return new MockMultipartFile("file", filename, "image/png", out.toByteArray());
    }
}
