package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.Topping;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.ToppingRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductOptionService {

    private static final BigDecimal MAX_EXTRA_PRICE =
        new BigDecimal("999999999999.99");

    private final ProductRepository productRepository;
    private final ProductSizeRepository productSizeRepository;
    private final ToppingRepository toppingRepository;
    private final ProductToppingRepository productToppingRepository;

    @Transactional
    public void configureAllProducts() {
        ensureToppingCatalog();
        productRepository.findAll().forEach(this::configureDefaults);
    }

    @Transactional
    public void configureDefaults(Product product) {
        if (isToppingProduct(product)) {
            productSizeRepository.deleteByProductId(product.getId());
            productToppingRepository.deleteByProductId(product.getId());
            return;
        }

        // Giữ nguyên cấu hình của sản phẩm đã có size.
        if (productSizeRepository.existsByProductId(product.getId())) {
            return;
        }

        upsertSize(product, "M", BigDecimal.ZERO, true, 0);
        upsertSize(product, "L", new BigDecimal("7000"), true, 1);
        upsertSize(product, "XL", new BigDecimal("12000"), true, 2);

        if (!productToppingRepository.existsByProductId(product.getId())) {
            for (Topping topping : ensureToppingCatalog()) {
                ProductTopping link = new ProductTopping();
                link.setProduct(product);
                link.setTopping(topping);
                productToppingRepository.save(link);
            }
        }
    }

    public List<Topping> availableToppings() {
        return toppingRepository.findByActiveTrueOrderByNameAsc();
    }

    public List<ProductSize> availableSizes(Product product) {
        return productSizeRepository
            .findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(
                product.getId()
            );
    }

    public List<ProductTopping> availableToppings(Product product) {
        return productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(
                product.getId()
            );
    }

    public void applyNewProductDefaults(ProductForm form) {
        form.setToppingIds(
            availableToppings().stream()
                .map(Topping::getId)
                .toList()
        );
    }

    public void loadConfiguration(Product product, ProductForm form) {
        form.setSizeMActive(false);
        form.setSizeLActive(false);
        form.setSizeXlActive(false);

        List<ProductSize> sizes = productSizeRepository
            .findByProductIdOrderByDisplayOrderAscIdAsc(product.getId());

        for (ProductSize size : sizes) {
            switch (size.getCode().toUpperCase(Locale.ROOT)) {
                case "M" -> {
                    form.setSizeMActive(size.isActive());
                    form.setSizeMExtraPrice(size.getExtraPrice());
                }
                case "L" -> {
                    form.setSizeLActive(size.isActive());
                    form.setSizeLExtraPrice(size.getExtraPrice());
                }
                case "XL" -> {
                    form.setSizeXlActive(size.isActive());
                    form.setSizeXlExtraPrice(size.getExtraPrice());
                }
                default -> {
                }
            }
        }

        form.setToppingIds(
            availableToppings(product).stream()
                .map(link -> link.getTopping().getId())
                .toList()
        );
    }

    @Transactional
    public void saveConfiguration(Product product, ProductForm form) {
        if (isToppingProduct(product)) {
            productSizeRepository.deleteByProductId(product.getId());
            productToppingRepository.deleteByProductId(product.getId());
            return;
        }

        if (!form.isSizeMActive()
                && !form.isSizeLActive()
                && !form.isSizeXlActive()) {
            throw new IllegalArgumentException(
                "Sản phẩm đồ uống phải có ít nhất một size đang bán."
            );
        }

        validateExtraPrice("M", form.getSizeMExtraPrice());
        validateExtraPrice("L", form.getSizeLExtraPrice());
        validateExtraPrice("XL", form.getSizeXlExtraPrice());

        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(
            form.getToppingIds() == null
                ? List.of()
                : form.getToppingIds()
        );

        if (selectedIds.stream().anyMatch(
                id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                "Danh sách topping có mã không hợp lệ."
            );
        }

        List<Topping> selected = selectedIds.isEmpty()
            ? List.of()
            : toppingRepository.findAllById(selectedIds).stream()
                .filter(Topping::isActive)
                .toList();

        if (selected.size() != selectedIds.size()) {
            throw new IllegalArgumentException(
                "Danh sách topping có mục không tồn tại hoặc đã ngừng bán."
            );
        }

        upsertSize(
            product, "M",
            form.getSizeMExtraPrice(), form.isSizeMActive(), 0
        );
        upsertSize(
            product, "L",
            form.getSizeLExtraPrice(), form.isSizeLActive(), 1
        );
        upsertSize(
            product, "XL",
            form.getSizeXlExtraPrice(), form.isSizeXlActive(), 2
        );

        productToppingRepository.deleteByProductId(product.getId());
        productToppingRepository.flush();

        List<ProductTopping> links = new ArrayList<>();

        for (Topping topping : selected) {
            ProductTopping link = new ProductTopping();
            link.setProduct(product);
            link.setTopping(topping);
            links.add(link);
        }

        productToppingRepository.saveAll(links);
    }

    private boolean isToppingProduct(Product product) {
        return product.getCategory() != null
            && "topping".equals(product.getCategory().getSlug());
    }

    private void validateExtraPrice(String code, BigDecimal extraPrice) {
        if (extraPrice == null
                || extraPrice.signum() < 0
                || extraPrice.compareTo(MAX_EXTRA_PRICE) > 0
                || extraPrice.scale() > 2) {
            throw new IllegalArgumentException(
                "Phụ thu size " + code
                    + " phải không âm, tối đa 12 chữ số nguyên"
                    + " và 2 chữ số thập phân."
            );
        }
    }

    private void upsertSize(
            Product product,
            String code,
            BigDecimal extraPrice,
            boolean active,
            int displayOrder) {

        ProductSize size = productSizeRepository
            .findByProductIdAndCodeIgnoreCase(product.getId(), code)
            .orElseGet(ProductSize::new);

        size.setProduct(product);
        size.setCode(code);
        size.setExtraPrice(extraPrice);
        size.setActive(active);
        size.setDisplayOrder(displayOrder);

        productSizeRepository.save(size);
    }

    private List<Topping> ensureToppingCatalog() {
        createTopping("Trân châu đen", "10000");
        createTopping("Trân châu trắng", "10000");
        createTopping("Pudding trứng", "12000");
        createTopping("Kem cheese", "15000");
        createTopping("Thạch dừa", "8000");

        return availableToppings();
    }

    private void createTopping(String name, String price) {
        if (toppingRepository.findByNameIgnoreCase(name).isPresent()) {
            return;
        }

        Topping topping = new Topping();
        topping.setName(name);
        topping.setPrice(new BigDecimal(price));
        topping.setActive(true);

        toppingRepository.save(topping);
    }
}