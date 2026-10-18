package vn.iotstar.config;

import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.util.SlugUtils;
import vn.iotstar.service.ProductOptionService;

@Configuration
@RequiredArgsConstructor
public class DataInitializer {
    private static final String[] SEEDED_PRODUCT_IMAGES = {
        "/images/products/01-tra-sua-tran-chau-duong-den.jpg",
        "/images/products/02-tra-sua-truyen-thong.jpg",
        "/images/products/03-tra-sua-o-long.jpg",
        "/images/products/04-tra-sua-matcha.jpg",
        "/images/products/05-tra-sua-khoai-mon.jpg",
        "/images/products/06-tra-dao-cam-sa.jpg",
        "/images/products/07-tra-vai-hoa-hong.jpg",
        "/images/products/08-tra-dau-nhiet-doi.jpg",
        "/images/products/09-tra-chanh-day.jpg",
        "/images/products/10-tra-xoai-macchiato.jpg",
        "/images/products/11-ca-phe-sua-da.jpg",
        "/images/products/12-bac-xiu.jpg",
        "/images/products/13-ca-phe-muoi.jpg",
        "/images/products/14-americano.jpg",
        "/images/products/15-latte-caramel.jpg",
        "/images/products/16-tra-sua-socola.jpg",
        "/images/products/17-o-long-kem-cheese.jpg",
        "/images/products/18-hong-tra-sua.jpg",
        "/images/products/19-tra-sua-dau.jpg",
        "/images/products/20-tra-sua-caramel.jpg",
        "/images/products/21-tra-tac-mat-ong.jpg",
        "/images/products/22-tra-nhan-sen.jpg",
        "/images/products/23-tra-oi-hong.jpg",
        "/images/products/24-tra-chanh-gia-tay.jpg",
        "/images/products/25-tra-man-do.jpg"
    };

    private static final String[] SEEDED_PRODUCT_DESCRIPTIONS = {
        "Sữa trà béo thơm quyện đường đen caramel và trân châu dai mềm đầy cuốn hút.",
        "Vị trà sữa nguyên bản thanh dịu, thơm trà và béo vừa đủ cho mọi ngày.",
        "Hương ô long rang sâu, hậu vị thanh tao hòa cùng lớp sữa mềm mượt.",
        "Matcha xanh thơm dịu, chát nhẹ cân bằng cùng sữa béo và trân châu mềm.",
        "Khoai môn bùi thơm, sắc tím dịu mắt và vị sữa ngọt ngào vừa phải.",
        "Trà đào mọng vị kết hợp cam tươi và sả thơm, mát lành đến ngụm cuối.",
        "Vải mọng nước hòa hương hoa hồng tinh tế, thanh mát và nhẹ nhàng.",
        "Dâu chua ngọt cùng trái cây nhiệt đới rực rỡ, tươi mát đầy năng lượng.",
        "Chanh dây chua thanh, thơm nồng tự nhiên và cực kỳ sảng khoái.",
        "Xoài chín vàng thơm ngọt phủ macchiato mịn béo, hài hòa khó cưỡng.",
        "Cà phê Việt đậm đà hòa sữa đặc béo thơm, tỉnh táo theo cách thật ngon.",
        "Nhiều sữa, ít cà phê, mềm mại và ngọt dịu dành cho người mê vị béo.",
        "Cà phê rang đậm phủ kem muối mằn mặn, béo mượt và đầy chiều sâu.",
        "Cà phê đen thanh sạch, hương rang rõ nét và hậu vị mạnh mẽ, tỉnh táo.",
        "Latte êm mượt điểm caramel ngọt thơm, dịu dàng mà vẫn đủ đậm vị.",
        "Socola đậm thơm hòa trà sữa béo mịn, thêm trân châu dai vui miệng.",
        "Ô long thanh thơm bên dưới lớp kem cheese mằn mặn, béo mịn hấp dẫn.",
        "Hồng trà đậm hương, vị sữa cân bằng và hậu ngọt dịu lưu lại thật lâu.",
        "Dâu tươi chua ngọt quyện sữa hồng mềm mại, thơm ngon và bắt mắt.",
        "Trà sữa béo dịu cùng caramel thơm nồng, ngọt êm trong từng ngụm.",
        "Tắc tươi chua thanh quyện mật ong dịu ngọt, giải khát cực đã.",
        "Nhãn ngọt mọng và hạt sen bùi nhẹ trong nền trà thanh mát tinh tế.",
        "Ổi hồng thơm dịu, chua ngọt tự nhiên và mang sắc hồng tươi mát.",
        "Chanh tươi giã tay dậy tinh dầu, hòa trà thơm cho vị chua thanh sảng khoái.",
        "Mận đỏ chua ngọt đậm đà, màu ruby bắt mắt và hậu vị tươi mát."
    };

    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CarrierRepository carrierRepository;
    private final VoucherRepository voucherRepository;
    private final PromotionRepository promotionRepository;
    private final ManagerShopRepository managerShopRepository;
    private final PasswordEncoder passwordEncoder;
    private final ProductOptionService productOptionService;

    @Bean
    @ConditionalOnProperty(name = "app.seed-data", havingValue = "true")
    CommandLineRunner seedUTETra() {
        return args -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Dữ liệu demo chỉ được tạo khi DB thật sự trống. Không chỉnh sửa
            // tài khoản/sản phẩm của TV1-TV2 hoặc dữ liệu kinh doanh hiện có.
            if (userRepository.count() > 0 || shopRepository.count() > 0
                || categoryRepository.count() > 0 || productRepository.count() > 0) {
                return;
            }

            User admin = user("admin", "admin@utetra.vn", "Quản trị UTETra", "Admin@123", Role.ADMIN);
            User manager = user("manager", "manager@utetra.vn", "Quản lý hệ thống", "Manager@123", Role.MANAGER);
            User vendor = user("vendor", "vendor@utetra.vn", "Chủ shop Thủ Đức", "Vendor@123", Role.VENDOR);
            User vendor2 = user("vendor2", "vendor2@utetra.vn", "Chủ shop Quận 9", "Vendor@123", Role.VENDOR);
            User shipper = user("shipper", "shipper@utetra.vn", "Shipper UTETra", "Shipper@123", Role.SHIPPER);
            User customer = user("user", "user@utetra.vn", "Khách hàng dùng thử", "User@123", Role.USER);
            userRepository.saveAll(List.of(admin, manager, vendor, vendor2, shipper, customer));

            Category milkTea = category("Trà sữa", "Trà sữa truyền thống và hiện đại", "/images/category-milktea.svg");
            Category fruitTea = category("Trà trái cây", "Trà tươi kết hợp trái cây", "/images/category-fruit.svg");
            Category coffee = category("Cà phê", "Cà phê rang xay và cà phê sữa", "/images/category-coffee.svg");
            Category topping = category("Topping", "Các loại topping dùng kèm", "/images/category-topping.svg");
            categoryRepository.saveAll(List.of(milkTea, fruitTea, coffee, topping));

            Shop shop1 = shop(vendor, "UTETra Thủ Đức", "Chi nhánh gần HCMUTE, chuyên trà sữa tươi và topping nhà làm.", "utetra-thu-duc");
            Shop shop2 = shop(vendor2, "UTETra Quận 9", "Chi nhánh phục vụ nhanh khu vực Quận 9 và lân cận.", "utetra-quan-9");
            shopRepository.saveAll(List.of(shop1, shop2));
            ensureManagerAssignment(manager, shop1, admin);

            String[] names = {
                "Trà sữa trân châu đường đen", "Trà sữa truyền thống", "Trà sữa ô long", "Trà sữa matcha", "Trà sữa khoai môn",
                "Trà đào cam sả", "Trà vải hoa hồng", "Trà dâu nhiệt đới", "Trà chanh dây", "Trà xoài macchiato",
                "Cà phê sữa đá", "Bạc xỉu", "Cà phê muối", "Americano", "Latte caramel",
                "Trà sữa socola", "Ô long kem cheese", "Hồng trà sữa", "Trà sữa dâu", "Trà sữa caramel",
                "Trà tắc mật ong", "Trà nhãn sen", "Trà ổi hồng", "Trà chanh giã tay", "Trà mận đỏ"
            };
            for (int i = 0; i < names.length; i++) {
                Category category = i >= 10 && i <= 14 ? coffee : (i >= 5 && i <= 9) || i >= 20 ? fruitTea : milkTea;
                Shop shop = i % 2 == 0 ? shop1 : shop2;
                Product product = new Product();
                product.setShop(shop);
                product.setCategory(category);
                product.setName(names[i]);
                product.setSlug(SlugUtils.toSlug(names[i]));
                product.setSku("UTE-" + String.format("%03d", i + 1));
                product.setDescription(SEEDED_PRODUCT_DESCRIPTIONS[i]);
                product.setImageUrl(SEEDED_PRODUCT_IMAGES[i]);
                product.setPrice(BigDecimal.valueOf(25000 + (i % 7) * 5000L));
                product.setStock(60 + i);
                product.setSoldCount(12 + (names.length - i) * 3L);
                product.setFavoriteCount(8 + i * 2L);
                product.setAverageRating(new BigDecimal("4.50"));
                product.setReviewCount(5 + i);
                productRepository.save(product);
            }
            ensureSeedToppingProducts();
            productOptionService.configureAllProducts();

            Carrier standard = carrier("UTETra Express", "18000");
            Carrier fast = carrier("Giao nhanh nội thành", "28000");
            carrierRepository.saveAll(List.of(standard, fast));

            Voucher welcome = voucher("WELCOME20", "Giảm 20% cho khách mới", VoucherType.PERCENT, "20", "80000", "40000");
            Voucher freeship = voucher("FREESHIP", "Hỗ trợ phí vận chuyển", VoucherType.SHIPPING, "18000", "50000", "18000");
            voucherRepository.saveAll(List.of(welcome, freeship));

            Promotion promotion = new Promotion();
            promotion.setName("Tuần lễ trà sữa UTETra");
            promotion.setType(PromotionType.PRODUCT_PERCENT);
            promotion.setDiscountValue(new BigDecimal("10"));
            promotion.setShop(shop1);
            promotion.setCreatedBy(vendor);
            promotion.setStartsAt(LocalDateTime.now().minusDays(1));
            promotion.setEndsAt(LocalDateTime.now().plusDays(30));
            promotionRepository.save(promotion);
        });
    }

    private void refreshSeedProductContent() {
        for (int i = 0; i < SEEDED_PRODUCT_IMAGES.length; i++) {
            String sku = "UTE-" + String.format("%03d", i + 1);
            String imageUrl = SEEDED_PRODUCT_IMAGES[i];
            String description = SEEDED_PRODUCT_DESCRIPTIONS[i];
            seedProduct(sku)
                .ifPresent(product -> {
                    boolean changed = false;
                    if (product.getImageUrl() == null || product.getImageUrl().isBlank()
                        || "/images/product-placeholder.svg".equals(product.getImageUrl())) {
                        product.setImageUrl(imageUrl);
                        changed = true;
                    }
                    if (product.getDescription() == null || product.getDescription().isBlank()
                        || (product.getDescription().startsWith("Ly ")
                            && product.getDescription().contains("Có thể điều chỉnh đá, đường"))) {
                        product.setDescription(description);
                        changed = true;
                    }
                    if (changed) productRepository.save(product);
                });
        }
    }

    private void ensureSeedToppingProducts() {
        Category topping = categoryRepository.findBySlugAndActiveTrue("topping").orElse(null);
        Shop shop1 = shopRepository.findBySlugAndStatusAndEnabledTrue("utetra-thu-duc", ShopStatus.APPROVED).orElse(null);
        Shop shop2 = shopRepository.findBySlugAndStatusAndEnabledTrue("utetra-quan-9", ShopStatus.APPROVED).orElse(null);
        if (topping == null || shop1 == null || shop2 == null) return;

        String[] names = {"Trân châu đường đen", "Thạch dừa", "Pudding trứng", "Kem cheese", "Hạt sen"};
        String[] descriptions = {
            "Trân châu nấu mới mỗi ngày, dai mềm vừa phải và áo lớp đường đen caramel thơm đậm.",
            "Thạch dừa trong veo, giòn nhẹ và thanh mát, thêm cảm giác vui miệng cho mọi ly trà.",
            "Pudding trứng vàng óng, mềm mịn và béo thơm, tan nhẹ khi thưởng thức cùng trà sữa.",
            "Kem cheese sánh mịn, béo dịu pha chút mằn mặn, tạo lớp vị cân bằng đầy cuốn hút.",
            "Hạt sen ninh mềm bùi, ngọt thanh tự nhiên, hợp với cả trà sữa lẫn trà trái cây."
        };
        String[] images = {
            "/images/products/26-tran-chau-duong-den.png", "/images/products/27-thach-dua.png",
            "/images/products/28-pudding-trung.png", "/images/products/29-kem-cheese.png",
            "/images/products/30-hat-sen.png"
        };
        long[] prices = {10000, 8000, 12000, 15000, 12000};

        for (int i = 0; i < names.length; i++) {
            String sku = "UTE-" + String.format("%03d", i + 26);
            Product product = seedProduct(sku).orElseGet(Product::new);
            boolean isNew = product.getId() == null;
            if (isNew) {
                product.setShop(i % 2 == 0 ? shop1 : shop2);
                product.setCategory(topping);
                product.setName(names[i]);
                product.setSlug(SlugUtils.toSlug(names[i]));
                product.setSku(sku);
                product.setPrice(BigDecimal.valueOf(prices[i]));
                product.setStock(100 + i * 10);
                product.setSoldCount(28 + (names.length - i) * 4L);
                product.setFavoriteCount(6 + i * 2L);
                product.setAverageRating(new BigDecimal("4.50"));
                product.setReviewCount(3 + i);
            }
            product.setDescription(descriptions[i]);
            product.setImageUrl(images[i]);
            productRepository.save(product);
        }
    }

    private Optional<Product> seedProduct(String sku) {
        return entityManager.createQuery(
            "select p from Product p where upper(p.sku) = upper(:sku)", Product.class)
            .setParameter("sku", sku).getResultList().stream().findFirst();
    }

    private User user(String username, String email, String fullName, String password, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    private Category category(String name, String description, String image) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(SlugUtils.toSlug(name));
        category.setDescription(description);
        category.setImageUrl(image);
        return category;
    }

    private Shop shop(User owner, String name, String description, String slug) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(name);
        shop.setDescription(description);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        shop.setImageUrl("/images/shop-placeholder.svg");
        applySeedShopProfile(shop);
        return shop;
    }

    private void refreshSeedShopProfiles() {
        shopRepository.findBySlug("utetra-thu-duc").ifPresent(shop -> {
            applySeedShopProfile(shop);
            shopRepository.save(shop);
        });
        shopRepository.findBySlug("utetra-quan-9").ifPresent(shop -> {
            applySeedShopProfile(shop);
            shopRepository.save(shop);
        });
    }

    private void ensureManagerAssignment(User manager, Shop shop, User assignedBy) {
        if (managerShopRepository.existsByManagerIdAndShopId(manager.getId(), shop.getId())) return;
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(assignedBy);
        managerShopRepository.save(assignment);
    }

    private void applySeedShopProfile(Shop shop) {
        boolean thuDuc = "utetra-thu-duc".equals(shop.getSlug());
        if (shop.getPhone() == null) shop.setPhone(thuDuc ? "02838968641" : "02837360001");
        if (shop.getEmail() == null) shop.setEmail(thuDuc ? "thuduc@utetra.vn" : "quan9@utetra.vn");
        if (shop.getAddress() == null) shop.setAddress(thuDuc ? "01 Võ Văn Ngân" : "01 Lê Văn Việt");
        if (shop.getWard() == null) shop.setWard(thuDuc ? "Phường Linh Chiểu" : "Phường Hiệp Phú");
        if (shop.getDistrict() == null) shop.setDistrict("TP. Thủ Đức");
        if (shop.getProvince() == null) shop.setProvince("TP. Hồ Chí Minh");
        if (shop.getLatitude() == null) shop.setLatitude(new BigDecimal(thuDuc ? "10.8507000" : "10.8440000"));
        if (shop.getLongitude() == null) shop.setLongitude(new BigDecimal(thuDuc ? "106.7719000" : "106.7899000"));
        if (shop.getOpeningTime() == null) shop.setOpeningTime(LocalTime.of(8, 0));
        if (shop.getClosingTime() == null) shop.setClosingTime(thuDuc ? LocalTime.of(22, 0) : LocalTime.of(22, 30));
    }

    private Carrier carrier(String name, String fee) {
        Carrier carrier = new Carrier();
        carrier.setName(name);
        carrier.setShippingFee(new BigDecimal(fee));
        return carrier;
    }

    private Voucher voucher(String code, String name, VoucherType type, String value, String minimum, String max) {
        Voucher voucher = new Voucher();
        voucher.setCode(code);
        voucher.setName(name);
        voucher.setType(type);
        voucher.setDiscountValue(new BigDecimal(value));
        voucher.setMinimumOrder(new BigDecimal(minimum));
        voucher.setMaxDiscount(new BigDecimal(max));
        voucher.setQuantity(100);
        voucher.setMaxUsesPerUser(1);
        voucher.setStartsAt(LocalDateTime.now().minusDays(1));
        voucher.setEndsAt(LocalDateTime.now().plusMonths(6));
        return voucher;
    }
}
