package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
public class ProductOptionService {
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
    public void reconcileDemoToppingCatalog() {
        Product lotusProduct = productRepository.findBySkuIgnoreCase("UTE-030").orElse(null);
        if (lotusProduct == null || !"Hạt sen".equals(lotusProduct.getName())
            || lotusProduct.getCategory() == null
            || !"topping".equals(lotusProduct.getCategory().getSlug())) return;
        if (toppingRepository.findByNameIgnoreCase("Trân châu đen").isEmpty()
            || toppingRepository.findByNameIgnoreCase("Trân châu đường đen").isPresent()) return;

        boolean lotusMissing = toppingRepository.findByNameIgnoreCase("Hạt sen").isEmpty();
        renameLegacyBlackPearl();
        if (!lotusMissing) return;
        createTopping("Hạt sen", lotusProduct.getPrice().toPlainString());
        Topping lotus = toppingRepository.findByNameIgnoreCase("Hạt sen").orElseThrow();
        Product lotusTea = productRepository.findBySkuIgnoreCase("UTE-022").orElse(null);
        if (lotusTea == null || !"Trà nhãn sen".equals(lotusTea.getName()) || !lotus.isActive()
            || productToppingRepository.existsByProductIdAndToppingId(lotusTea.getId(), lotus.getId())) return;

        List<ProductTopping> selected = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(lotusTea.getId());
        Set<String> defaults = Set.of("Trân châu đường đen", "Trân châu trắng", "Pudding trứng", "Kem cheese", "Thạch dừa");
        if (selected.size() == defaults.size()
            && selected.stream().map(link -> link.getTopping().getName()).collect(java.util.stream.Collectors.toSet()).equals(defaults)) {
            ProductTopping link = new ProductTopping();
            link.setProduct(lotusTea);
            link.setTopping(lotus);
            productToppingRepository.save(link);
        }
    }

    @Transactional
    public void configureDefaults(Product product) {
        if (product.getCategory() != null && "topping".equals(product.getCategory().getSlug())) {
            productSizeRepository.deleteByProductId(product.getId());
            productToppingRepository.deleteByProductId(product.getId());
            return;
        }
        if (productSizeRepository.existsByProductId(product.getId())
            || productToppingRepository.existsByProductId(product.getId())) return;
        addSize(product, "M", BigDecimal.ZERO, 0);
        addSize(product, "L", new BigDecimal("7000"), 1);
        addSize(product, "XL", new BigDecimal("12000"), 2);
        for (Topping topping : ensureToppingCatalog()) {
            if (!productToppingRepository.existsByProductIdAndToppingId(product.getId(), topping.getId())) {
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
        return productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId());
    }

    public List<ProductTopping> availableToppings(Product product) {
        return productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId());
    }

    public void applyNewProductDefaults(ProductForm form) {
        form.setToppingIds(availableToppings().stream().map(Topping::getId).toList());
    }

    public void loadConfiguration(Product product, ProductForm form) {
        for (ProductSize size : productSizeRepository.findByProductIdOrderByDisplayOrderAscIdAsc(product.getId())) {
            switch (size.getCode().toUpperCase(Locale.ROOT)) {
                case "M" -> { form.setSizeMActive(size.isActive()); form.setSizeMExtraPrice(size.getExtraPrice()); }
                case "L" -> { form.setSizeLActive(size.isActive()); form.setSizeLExtraPrice(size.getExtraPrice()); }
                case "XL" -> { form.setSizeXlActive(size.isActive()); form.setSizeXlExtraPrice(size.getExtraPrice()); }
                default -> { }
            }
        }
        form.setToppingIds(productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId()).stream()
            .map(link -> link.getTopping().getId()).toList());
    }

    @Transactional
    public void saveConfiguration(Product product, ProductForm form) {
        if (product.getCategory() != null && "topping".equals(product.getCategory().getSlug())) {
            productSizeRepository.deleteByProductId(product.getId());
            productToppingRepository.deleteByProductId(product.getId());
            return;
        }
        if (!form.isSizeMActive() && !form.isSizeLActive() && !form.isSizeXlActive()) {
            throw new IllegalArgumentException("Sản phẩm đồ uống phải có ít nhất một size đang bán.");
        }
        upsertSize(product, "M", form.getSizeMExtraPrice(), form.isSizeMActive(), 0);
        upsertSize(product, "L", form.getSizeLExtraPrice(), form.isSizeLActive(), 1);
        upsertSize(product, "XL", form.getSizeXlExtraPrice(), form.isSizeXlActive(), 2);

        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(
            form.getToppingIds() == null ? List.of() : form.getToppingIds());
        List<Topping> selected = selectedIds.isEmpty() ? List.of() : toppingRepository.findAllById(selectedIds).stream()
            .filter(Topping::isActive).toList();
        if (selected.size() != selectedIds.size()) {
            throw new IllegalArgumentException("Danh sách topping áp dụng có mục không hợp lệ hoặc đã ngừng bán.");
        }
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

    private void upsertSize(Product product, String code, BigDecimal extraPrice, boolean active, int displayOrder) {
        if (extraPrice == null || extraPrice.signum() < 0) {
            throw new IllegalArgumentException("Phụ thu size " + code + " không hợp lệ.");
        }
        ProductSize size = productSizeRepository.findByProductIdAndCodeIgnoreCase(product.getId(), code)
            .orElseGet(ProductSize::new);
        size.setProduct(product);
        size.setCode(code);
        size.setExtraPrice(extraPrice);
        size.setActive(active);
        size.setDisplayOrder(displayOrder);
        productSizeRepository.save(size);
    }

    private List<Topping> ensureToppingCatalog() {
        renameLegacyBlackPearl();
        createTopping("Trân châu đường đen", "10000");
        createTopping("Trân châu trắng", "10000");
        createTopping("Pudding trứng", "12000");
        createTopping("Kem cheese", "15000");
        createTopping("Thạch dừa", "8000");
        createTopping("Hạt sen", "12000");
        return toppingRepository.findByActiveTrueOrderByNameAsc();
    }

    private void renameLegacyBlackPearl() {
        if (toppingRepository.findByNameIgnoreCase("Trân châu đường đen").isPresent()) return;
        toppingRepository.findByNameIgnoreCase("Trân châu đen").ifPresent(topping -> {
            topping.setName("Trân châu đường đen");
            toppingRepository.save(topping);
        });
    }

    private void createTopping(String name, String price) {
        if (toppingRepository.findByNameIgnoreCase(name).isPresent()) return;
        Topping topping = new Topping();
        topping.setName(name);
        topping.setPrice(new BigDecimal(price));
        topping.setActive(true);
        toppingRepository.save(topping);
    }

    private void addSize(Product product, String code, BigDecimal extraPrice, int order) {
        if (productSizeRepository.existsByProductIdAndCodeIgnoreCase(product.getId(), code)) return;
        ProductSize size = new ProductSize();
        size.setProduct(product);
        size.setCode(code);
        size.setExtraPrice(extraPrice);
        size.setDisplayOrder(order);
        productSizeRepository.save(size);
    }
}
