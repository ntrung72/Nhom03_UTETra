package vn.iotstar.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Topping;
import vn.iotstar.entity.Promotion;
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

/** Step 13 only: dashboard shell, users, shops and Manager assignments. */
@Controller
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {
    private final CurrentUserService currentUserService;
    private final ManagementService managementService;
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
