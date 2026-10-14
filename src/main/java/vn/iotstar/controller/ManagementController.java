package vn.iotstar.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.dto.WebForms.CarrierForm;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Topping;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Voucher;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.service.ManagementProductService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductGalleryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.User;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.OrderService;

/** Step 13 only: dashboard shell, users, shops and Manager assignments. */
@Controller
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {
    private final CurrentUserService currentUserService;
    private final ManagementService managementService;
    private final OrderService orderService;
    private final ManagementProductService managementProductService;
    private final ProductOptionService productOptionService;
    private final ProductGalleryService productGalleryService;

    @ModelAttribute("currentUser")
    User currentUser() { return currentUserService.optional().orElse(null); }

    @GetMapping({"", "/"})
    String dashboard(Model model) {
        model.addAttribute("summary", managementService.dashboard(currentUserService.require()));
        return "management/dashboard";
    }


    // ==================== Step 21: Order Management ====================
    @GetMapping("/orders")
    String orders(@RequestParam(required = false) String q,
                  @RequestParam(required = false) Long shop,
                  @RequestParam(required = false) vn.iotstar.entity.DomainEnums.OrderStatus status,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        User actor = currentUserService.require();
        var orders = managementService.orders(actor, q, shop, status, page);
        var allowed = new java.util.HashMap<Long, java.util.List<vn.iotstar.entity.DomainEnums.OrderStatus>>();
        var assignable = new java.util.HashMap<Long, Boolean>();
        orders.forEach(o -> {
            allowed.put(o.getId(), orderService.managementTransitions(o.getStatus()));
            assignable.put(o.getId(), orderService.canAssignShipper(o.getStatus()));
        });
        model.addAttribute("orders", orders);
        model.addAttribute("allowedTransitions", allowed);
        model.addAttribute("assignableOrders", assignable);
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("shippers", managementService.shippers(actor));
        model.addAttribute("statuses", vn.iotstar.entity.DomainEnums.OrderStatus.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("selectedStatus", status);
        return "management/orders";
    }

    @PostMapping("/orders/{id}/assign")
    String assignShipper(@PathVariable Long id, @RequestParam Long shipperId, RedirectAttributes flash) {
        try {
            orderService.assignShipper(currentUserService.require(), id, shipperId);
            flash.addFlashAttribute("success", "Đã phân công shipper.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @PostMapping("/orders/{id}/retry-delivery")
    String retryDelivery(@PathVariable Long id, @RequestParam Long shipperId, RedirectAttributes flash) {
        try {
            orderService.retryFailedDelivery(currentUserService.require(), id, shipperId);
            flash.addFlashAttribute("success", "Đã điều phối giao lại.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @PostMapping("/orders/{id}/status")
    String managementOrderStatus(@PathVariable Long id,
              @RequestParam vn.iotstar.entity.DomainEnums.OrderStatus status, RedirectAttributes flash) {
        try {
            orderService.managementUpdate(currentUserService.require(), id, status);
            flash.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @GetMapping("/users")
    String users(@RequestParam(required = false) String q,
                 @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", managementService.users(currentUserService.require(), q, page));
        model.addAttribute("roles", Role.values());
        model.addAttribute("q", q);
        return "management/users";
    }

    @PostMapping("/users/{id}/toggle-lock")
    String toggleUser(@PathVariable Long id, RedirectAttributes flash) {
        try {
            managementService.toggleUser(id, currentUserService.require());
            flash.addFlashAttribute("success", "Đã cập nhật trạng thái khóa tài khoản.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/users";
    }

    @PostMapping("/users/{id}/role")
    String changeRole(@PathVariable Long id, @RequestParam Role role, RedirectAttributes flash) {
        try {
            managementService.changeRole(id, role, currentUserService.require());
            flash.addFlashAttribute("success", "Đã cập nhật vai trò người dùng.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/users";
    }

    @GetMapping("/shops")
    String shops(@RequestParam(required = false) String q,
                 @RequestParam(required = false) ShopStatus status,
                 @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("shops", managementService.shops(currentUserService.require(), q, status, page));
        model.addAttribute("statuses", ShopStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("q", q);
        return "management/shops";
    }

    @PostMapping("/shops/{id}")
    String updateShop(@PathVariable Long id, @RequestParam ShopStatus status,
                      @RequestParam BigDecimal commissionRate, RedirectAttributes flash) {
        try {
            managementService.updateShop(currentUserService.require(), id, status, commissionRate);
            flash.addFlashAttribute("success", "Đã cập nhật cửa hàng.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/shops";
    }

    @GetMapping("/managers")
    String managers(Model model) {
        User actor = currentUserService.require();
        model.addAttribute("managers", managementService.managers(actor));
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("assignments", managementService.managerAssignments(actor));
        return "management/managers";
    }

    @PostMapping("/managers/{id}/shops")
    String assignShops(@PathVariable Long id, @RequestParam(required = false) List<Long> shopIds,
                       RedirectAttributes flash) {
        try {
            managementService.assignManagerShops(currentUserService.require(), id, shopIds);
            flash.addFlashAttribute("success", "Đã cập nhật phân công cửa hàng cho Manager.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/managers";
    }

    // ==================== STEP 14: Management Catalog ====================

    @GetMapping("/categories")
    String categories(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("categories", managementService.categories(q, page));
        model.addAttribute("q", q);
        return "management/categories";
    }

    @GetMapping("/categories/new")
    String newCategory(Model model) {
        model.addAttribute("categoryForm", new CategoryForm());
        return "management/category-form";
    }

    @GetMapping("/categories/{id}/edit")
    String editCategory(@PathVariable Long id, Model model) {
        Category category = managementService.getCategory(id);
        CategoryForm form = new CategoryForm();
        form.setName(category.getName());
        form.setDescription(category.getDescription());
        form.setActive(category.isActive());
        model.addAttribute("categoryForm", form);
        model.addAttribute("category", category);
        return "management/category-form";
    }

    @PostMapping("/categories/save")
    String saveCategory(@RequestParam(required = false) Long id,
                        @Valid @ModelAttribute CategoryForm categoryForm, BindingResult result,
                        @RequestParam(required = false) MultipartFile image, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            if (id != null) model.addAttribute("category", managementService.getCategory(id));
            return "management/category-form";
        }
        managementService.saveCategory(id, categoryForm, image);
        redirect.addFlashAttribute("success", "Đã lưu danh mục.");
        return "redirect:/management/categories";
    }

    @PostMapping("/categories/{id}/toggle")
    String toggleCategory(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleCategory(id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái danh mục.");
        return "redirect:/management/categories";
    }

    @GetMapping("/products")
    String products(@RequestParam(required = false) String q, @RequestParam(required = false) Long shop,
                    @RequestParam(required = false) Long category, @RequestParam(defaultValue = "0") int page, Model model) {
        User actor = currentUserService.require();
        model.addAttribute("products", managementService.products(actor, q, shop, category, page));
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("categories", managementService.allCategories());
        model.addAttribute("q", q);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("selectedCategory", category);
        return "management/products";
    }

    @GetMapping("/products/new")
    String newProduct(Model model) {
        ProductForm form = new ProductForm();
        productOptionService.applyNewProductDefaults(form);
        prepareManagementProductForm(model, form, null, null);
        return "management/product-form";
    }

    @GetMapping("/products/{id}")
    String productDetail(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        try {
            Product product = managementProductService.get(currentUserService.require(), id);
            model.addAttribute("product", product);
            model.addAttribute("gallery", productGalleryService.displayImages(product));
            model.addAttribute("sizes", productOptionService.availableSizes(product));
            model.addAttribute("productToppings", productOptionService.availableToppings(product));
            return "management/product-detail";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/management/products";
        }
    }

    @GetMapping("/products/{id}/edit")
    String editProduct(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        try {
            Product product = managementProductService.get(currentUserService.require(), id);
            ProductForm form = productForm(product);
            productOptionService.loadConfiguration(product, form);
            prepareManagementProductForm(model, form, product, product.getShop().getId());
            return "management/product-form";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/management/products";
        }
    }

    @PostMapping("/products/save")
    String saveProduct(@RequestParam(required = false) Long id, @RequestParam Long shopId,
                       @Valid @ModelAttribute ProductForm productForm, BindingResult result,
                       @RequestParam(required = false) MultipartFile image,
                       @RequestParam(required = false) MultipartFile[] galleryImages,
                       @RequestParam(required = false) java.util.List<Long> removeImageIds,
                       @RequestParam(required = false) java.util.List<Long> galleryOrder,
                       @RequestParam(required = false) Long primaryImageId,
                       Model model, RedirectAttributes redirect) {
        User actor = currentUserService.require();
        Product product;
        try {
            product = id == null ? null : managementProductService.get(actor, id);
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/management/products";
        }
        if (result.hasErrors()) {
            prepareManagementProductForm(model, productForm, product, shopId);
            return "management/product-form";
        }
        try {
            Product saved = managementProductService.save(actor, id, shopId, productForm, image,
                galleryImages, removeImageIds, galleryOrder, primaryImageId);
            redirect.addFlashAttribute("success", "Đã lưu sản phẩm và cấu hình bán hàng.");
            return "redirect:/management/products/" + saved.getId();
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            prepareManagementProductForm(model, productForm, product, shopId);
            return "management/product-form";
        }
    }

    @PostMapping("/products/{id}/toggle")
    String toggleProduct(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleProduct(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái sản phẩm.");
        return "redirect:/management/products";
    }


    @GetMapping("/toppings")
    String toppings(@RequestParam(required = false) String q,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("toppings", managementService.toppings(q, page));
        model.addAttribute("q", q);
        return "management/toppings";
    }

    @GetMapping("/toppings/new")
    String newTopping(Model model) {
        model.addAttribute("toppingForm", new ToppingForm());
        return "management/topping-form";
    }

    @GetMapping("/toppings/{id}/edit")
    String editTopping(@PathVariable Long id, Model model) {
        Topping topping = managementService.getTopping(id);
        ToppingForm form = new ToppingForm();
        form.setName(topping.getName());
        form.setPrice(topping.getPrice());
        form.setActive(topping.isActive());
        prepareToppingForm(model, topping, form);
        return "management/topping-form";
    }

    @PostMapping("/toppings/save")
    String saveTopping(@RequestParam(required = false) Long id,
                       @Valid @ModelAttribute("toppingForm") ToppingForm form,
                       BindingResult result, Model model, RedirectAttributes redirect) {
        if (!result.hasErrors()) {
            try {
                managementService.saveTopping(id, form);
            } catch (IllegalArgumentException ex) {
                result.reject("topping.invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            prepareToppingForm(model, id == null ? null : managementService.getTopping(id), form);
            return "management/topping-form";
        }
        redirect.addFlashAttribute("success", "Đã lưu topping.");
        return "redirect:/management/toppings";
    }

    @PostMapping("/toppings/{id}/toggle")
    String toggleTopping(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleTopping(id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái topping.");
        return "redirect:/management/toppings";
    }

    @PostMapping("/toppings/{id}/delete")
    String deleteTopping(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            managementService.deleteTopping(id);
            redirect.addFlashAttribute("success", "Đã xóa topping.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/toppings";
    }

    private void prepareToppingForm(Model model, Topping topping, ToppingForm form) {
        model.addAttribute("toppingForm", form);
        model.addAttribute("topping", topping);
        model.addAttribute("appliedProductCount", topping == null ? 0 : managementService.toppingProductCount(topping.getId()));
    }

    @GetMapping("/promotions")
    String promotions(@RequestParam(required = false) String q,
                      @RequestParam(required = false) Long shop,
                      @RequestParam(required = false) PromotionType type,
                      @RequestParam(defaultValue = "0") int page, Model model) {
        User actor = currentUserService.require();
        model.addAttribute("promotions", managementService.promotions(actor, q, shop, type, page));
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("types", PromotionType.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("selectedType", type);
        return "management/promotions";
    }

    @GetMapping("/promotions/new")
    String newPromotion(Model model) {
        PromotionForm form = new PromotionForm();
        form.setStartsAt(LocalDateTime.now().withSecond(0).withNano(0));
        form.setEndsAt(LocalDateTime.now().plusDays(7).withSecond(0).withNano(0));
        preparePromotion(model, form, null, null);
        return "management/promotion-form";
    }

    @GetMapping("/promotions/{id}/edit")
    String editPromotion(@PathVariable Long id, Model model) {
        Promotion promotion = managementService.getPromotion(currentUserService.require(), id);
        PromotionForm form = new PromotionForm();
        form.setName(promotion.getName());
        form.setType(promotion.getType());
        form.setDiscountValue(promotion.getDiscountValue());
        form.setProductId(promotion.getProduct() == null ? null : promotion.getProduct().getId());
        form.setStartsAt(promotion.getStartsAt());
        form.setEndsAt(promotion.getEndsAt());
        form.setActive(promotion.isActive());
        preparePromotion(model, form, promotion, promotion.getShop() == null ? null : promotion.getShop().getId());
        return "management/promotion-form";
    }

    @PostMapping("/promotions/save")
    String savePromotion(@RequestParam(required = false) Long id, @RequestParam(required = false) Long shopId,
                         @Valid @ModelAttribute PromotionForm promotionForm, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            User actor = currentUserService.require();
            preparePromotion(model, promotionForm, id == null ? null : managementService.getPromotion(actor, id), shopId);
            return "management/promotion-form";
        }
        managementService.savePromotion(id, shopId, promotionForm, currentUserService.require());
        redirect.addFlashAttribute("success", "Đã lưu chương trình khuyến mãi.");
        return "redirect:/management/promotions";
    }

    @PostMapping("/promotions/{id}/delete")
    String deletePromotion(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.deletePromotion(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã xóa khuyến mãi.");
        return "redirect:/management/promotions";
    }


    // ==================== STEP 15: System Vouchers ====================

    @GetMapping("/vouchers")
    String vouchers(@RequestParam(required = false) String q,
                    @RequestParam(required = false) VoucherType type,
                    @RequestParam(required = false) Boolean active,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("vouchers", managementService.vouchers(currentUserService.require(), q, type, active, page));
        model.addAttribute("types", VoucherType.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedActive", active);
        model.addAttribute("now", LocalDateTime.now());
        return "management/vouchers";
    }

    @GetMapping("/vouchers/new")
    String newVoucher(Model model) {
        VoucherForm form = new VoucherForm();
        form.setQuantity(100);
        form.setStartsAt(LocalDateTime.now().withSecond(0).withNano(0));
        form.setEndsAt(LocalDateTime.now().plusMonths(1).withSecond(0).withNano(0));
        prepareVoucher(model, form, null);
        return "management/voucher-form";
    }

    @GetMapping("/vouchers/{id}/edit")
    String editVoucher(@PathVariable Long id, Model model) {
        Voucher voucher = managementService.getVoucher(currentUserService.require(), id);
        VoucherForm form = new VoucherForm();
        form.setCode(voucher.getCode());
        form.setName(voucher.getName());
        form.setType(voucher.getType());
        form.setDiscountValue(voucher.getDiscountValue());
        form.setMinimumOrder(voucher.getMinimumOrder());
        form.setMaxDiscount(voucher.getMaxDiscount());
        form.setQuantity(voucher.getQuantity());
        form.setMaxUsesPerUser(voucher.getEffectiveMaxUsesPerUser());
        form.setStartsAt(voucher.getStartsAt());
        form.setEndsAt(voucher.getEndsAt());
        form.setActive(voucher.isActive());
        prepareVoucher(model, form, voucher);
        return "management/voucher-form";
    }

    @PostMapping("/vouchers/save")
    String saveVoucher(@RequestParam(required = false) Long id,
                       @Valid @ModelAttribute("voucherForm") VoucherForm voucherForm,
                       BindingResult result, Model model, RedirectAttributes redirect) {
        if (!result.hasErrors()) {
            try {
                managementService.saveVoucher(currentUserService.require(), id, voucherForm);
            } catch (IllegalArgumentException ex) {
                result.reject("voucher.invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            prepareVoucher(model, voucherForm, id == null ? null : managementService.getVoucher(currentUserService.require(), id));
            return "management/voucher-form";
        }
        redirect.addFlashAttribute("success", "Đã lưu mã giảm giá.");
        return "redirect:/management/vouchers";
    }

    @PostMapping("/vouchers/{id}/toggle")
    String toggleVoucher(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleVoucher(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái mã giảm giá.");
        return "redirect:/management/vouchers";
    }

    @PostMapping("/vouchers/{id}/delete")
    String deleteVoucher(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            managementService.deleteVoucher(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã xóa mã giảm giá.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/vouchers";
    }

    @GetMapping("/vouchers/{id}/usages")
    String voucherUsages(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, Model model) {
        Voucher voucher = managementService.getVoucher(currentUserService.require(), id);
        model.addAttribute("voucher", voucher);
        model.addAttribute("usages", managementService.voucherUsages(currentUserService.require(), id, page));
        return "management/voucher-usages";
    }

    // ==================== STEP 16: Carriers ====================

    @GetMapping("/carriers")
    String carriers(@RequestParam(required = false) String q,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("carriers", managementService.carriers(currentUserService.require(), q, page));
        model.addAttribute("q", q);
        return "management/carriers";
    }

    @GetMapping("/carriers/new")
    String newCarrier(Model model) {
        model.addAttribute("carrierForm", new CarrierForm());
        return "management/carrier-form";
    }

    @GetMapping("/carriers/{id}/edit")
    String editCarrier(@PathVariable Long id, Model model) {
        Carrier carrier = managementService.getCarrier(currentUserService.require(), id);
        CarrierForm form = new CarrierForm();
        form.setName(carrier.getName());
        form.setShippingFee(carrier.getShippingFee());
        form.setActive(carrier.isActive());
        model.addAttribute("carrierForm", form);
        model.addAttribute("carrier", carrier);
        return "management/carrier-form";
    }

    @PostMapping("/carriers/save")
    String saveCarrier(@RequestParam(required = false) Long id,
                       @Valid @ModelAttribute("carrierForm") CarrierForm carrierForm,
                       BindingResult result, Model model, RedirectAttributes flash) {
        if (!result.hasErrors()) {
            try {
                managementService.saveCarrier(currentUserService.require(), id, carrierForm);
            } catch (IllegalArgumentException ex) {
                result.reject("carrier.invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            if (id != null) model.addAttribute("carrier", managementService.getCarrier(currentUserService.require(), id));
            return "management/carrier-form";
        }
        flash.addFlashAttribute("success", "Đã lưu nhà vận chuyển.");
        return "redirect:/management/carriers";
    }

    @PostMapping("/carriers/{id}/toggle")
    String toggleCarrier(@PathVariable Long id, RedirectAttributes flash) {
        managementService.toggleCarrier(currentUserService.require(), id);
        flash.addFlashAttribute("success", "Đã đổi trạng thái nhà vận chuyển.");
        return "redirect:/management/carriers";
    }

    @PostMapping("/carriers/{id}/delete")
    String deleteCarrier(@PathVariable Long id, RedirectAttributes flash) {
        try {
            managementService.deleteCarrier(currentUserService.require(), id);
            flash.addFlashAttribute("success", "Đã xóa nhà vận chuyển.");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            flash.addFlashAttribute("error", "Nhà vận chuyển đã được dùng trong đơn hàng; hãy tắt thay vì xóa.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/carriers";
    }

    private void prepareVoucher(Model model, VoucherForm form, Voucher voucher) {
        model.addAttribute("voucherForm", form);
        model.addAttribute("voucher", voucher);
        model.addAttribute("types", VoucherType.values());
    }

    private void preparePromotion(Model model, PromotionForm form, Promotion promotion, Long selectedShopId) {
        User actor = currentUserService.require();
        var accessibleShops = managementService.accessibleShops(actor);
        if (actor.getRole() == Role.MANAGER && selectedShopId == null && !accessibleShops.isEmpty()) {
            selectedShopId = accessibleShops.getFirst().getId();
        }
        model.addAttribute("promotionForm", form);
        model.addAttribute("promotion", promotion);
        model.addAttribute("selectedShopId", selectedShopId);
        model.addAttribute("shops", accessibleShops);
        model.addAttribute("products", managementService.accessibleProducts(actor));
    }

    private ProductForm productForm(Product product) {
        ProductForm form = new ProductForm();
        form.setName(product.getName());
        form.setSku(product.getSku());
        form.setCategoryId(product.getCategory().getId());
        form.setDescription(product.getDescription());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        form.setStatus(product.getStatus());
        return form;
    }

    private void prepareManagementProductForm(Model model, ProductForm form, Product product, Long selectedShopId) {
        User actor = currentUserService.require();
        var shops = managementService.accessibleShops(actor);
        if (product != null) selectedShopId = product.getShop().getId();
        if (selectedShopId == null && shops.size() == 1) selectedShopId = shops.getFirst().getId();
        model.addAttribute("editing", product != null);
        model.addAttribute("productId", product == null ? null : product.getId());
        model.addAttribute("shop", product == null ? null : product.getShop());
        model.addAttribute("sugarLevels", vn.iotstar.entity.DomainEnums.SugarLevel.values());
        model.addAttribute("iceLevels", vn.iotstar.entity.DomainEnums.IceLevel.values());
        model.addAttribute("statuses", vn.iotstar.entity.DomainEnums.ProductStatus.values());
        model.addAttribute("managementMode", true);
        model.addAttribute("productForm", form);
        model.addAttribute("product", product);
        model.addAttribute("shops", shops);
        model.addAttribute("selectedShopId", selectedShopId);
        model.addAttribute("categories", managementProductService.activeCategories());
        model.addAttribute("toppings", productOptionService.availableToppings());
        model.addAttribute("gallery", product == null ? java.util.List.of() : productGalleryService.images(product));
    }


}
