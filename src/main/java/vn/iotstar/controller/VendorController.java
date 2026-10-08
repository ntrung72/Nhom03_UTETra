package vn.iotstar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.VendorService;

@Controller
@RequestMapping("/vendor")
@RequiredArgsConstructor
public class VendorController {

    private final CurrentUserService currentUserService;
    private final VendorService vendorService;
    private final CustomUserDetailsService userDetailsService;
    private final ProductOptionService productOptionService;

    @ModelAttribute("currentUser")
    public User currentUser() {
        return currentUserService.optional().orElse(null);
    }

    @GetMapping({"", "/"})
    public String home() {
        return "redirect:/vendor/shop/edit";
    }

    @GetMapping("/register")
    public String register(Model model) {
        User user = currentUserService.require();

        if (vendorService.findShop(user) != null) {
            return "redirect:/vendor/shop/edit";
        }

        ShopForm form = new ShopForm();
        form.setEmail(user.getEmail());
        form.setOpeningTime(LocalTime.of(8, 0));
        form.setClosingTime(LocalTime.of(22, 0));

        model.addAttribute("shopForm", form);
        model.addAttribute("editing", false);
        return "vendor/shop-form";
    }

    @PostMapping("/register")
    public String register(
            @Valid @ModelAttribute("shopForm") ShopForm form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect,
            HttpServletRequest request,
            HttpServletResponse response) {

        model.addAttribute("editing", false);

        if (result.hasErrors()) {
            return "vendor/shop-form";
        }

        User user = currentUserService.require();

        try {
            vendorService.registerShop(user, form);
        } catch (IllegalArgumentException exception) {
            result.reject("shop.registration", exception.getMessage());
            return "vendor/shop-form";
        }

        refreshAuthentication(user.getUsername(), request, response);

        redirect.addFlashAttribute(
            "success",
            "Đăng ký cửa hàng thành công. Vui lòng chờ duyệt."
        );

        return "redirect:/vendor/shop/edit";
    }

    @GetMapping("/shop/edit")
    public String editShop(Model model) {
        Shop shop = vendorService.findShop(currentUserService.require());

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        ShopForm form = new ShopForm();
        form.setName(shop.getName());
        form.setDescription(shop.getDescription());
        form.setPhone(shop.getPhone());
        form.setEmail(shop.getEmail());
        form.setAddress(shop.getAddress());
        form.setProvince(shop.getProvince());
        form.setDistrict(shop.getDistrict());
        form.setWard(shop.getWard());
        form.setLatitude(shop.getLatitude());
        form.setLongitude(shop.getLongitude());
        form.setOpeningTime(shop.getOpeningTime());
        form.setClosingTime(shop.getClosingTime());

        model.addAttribute("shopForm", form);
        model.addAttribute("shop", shop);
        model.addAttribute("editing", true);
        return "vendor/shop-form";
    }

    @PostMapping("/shop/edit")
    public String editShop(
            @Valid @ModelAttribute("shopForm") ShopForm form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {

        User user = currentUserService.require();
        Shop shop = vendorService.findShop(user);

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        model.addAttribute("shop", shop);
        model.addAttribute("editing", true);

        if (result.hasErrors()) {
            return "vendor/shop-form";
        }

        try {
            vendorService.updateShop(user, form);
        } catch (IllegalArgumentException exception) {
            result.reject("shop.update", exception.getMessage());
            return "vendor/shop-form";
        }

        redirect.addFlashAttribute(
            "success",
            "Đã cập nhật thông tin cửa hàng."
        );

        return "redirect:/vendor/shop/edit";
    }

    @GetMapping("/products")
    public String products(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            Model model) {

        User user = currentUserService.require();
        Shop shop = vendorService.findShop(user);

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        model.addAttribute("shop", shop);
        model.addAttribute("page", vendorService.products(user, q, page));
        model.addAttribute("q", q);
        return "vendor/products";
    }

    @GetMapping("/products/new")
    public String newProduct(Model model) {
        Shop shop = vendorService.findShop(currentUserService.require());

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        ProductForm form = new ProductForm();
        productOptionService.applyNewProductDefaults(form);

        model.addAttribute("productForm", form);
        prepareProductForm(model, shop, null);
        return "vendor/product-form";
    }

    @GetMapping("/products/{id}/edit")
    public String editProduct(
            @PathVariable("id") Long id,
            Model model,
            RedirectAttributes redirect) {

        User user = currentUserService.require();
        Shop shop = vendorService.findShop(user);

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        Product product;

        try {
            product = vendorService.productForEdit(user, id);
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/vendor/products";
        }

        ProductForm form = new ProductForm();
        form.setName(product.getName());
        form.setSku(product.getSku());
        form.setCategoryId(product.getCategory().getId());
        form.setDescription(product.getDescription());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        form.setStatus(product.getStatus());

        productOptionService.loadConfiguration(product, form);

        model.addAttribute("productForm", form);
        model.addAttribute("product", product);
        prepareProductForm(model, shop, id);
        return "vendor/product-form";
    }

    @PostMapping({"/products/new", "/products/{id}/edit"})
    public String saveProduct(
            @PathVariable(name = "id", required = false) Long id,
            @Valid @ModelAttribute("productForm") ProductForm form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {

        User user = currentUserService.require();
        Shop shop = vendorService.findShop(user);

        if (shop == null) {
            return "redirect:/vendor/register";
        }

        if (id != null) {
            try {
                Product product = vendorService.productForEdit(user, id);
                model.addAttribute("product", product);
            } catch (IllegalArgumentException exception) {
                redirect.addFlashAttribute("error", exception.getMessage());
                return "redirect:/vendor/products";
            }
        }

        prepareProductForm(model, shop, id);

        if (result.hasErrors()) {
            return "vendor/product-form";
        }

        try {
            vendorService.saveProduct(user, id, form);
        } catch (IllegalArgumentException exception) {
            result.reject("product.save", exception.getMessage());
            return "vendor/product-form";
        }

        redirect.addFlashAttribute(
            "success",
            id == null
                ? "Đã thêm sản phẩm."
                : "Đã cập nhật sản phẩm."
        );

        return "redirect:/vendor/products";
    }

    @PostMapping("/products/{id}/toggle")
    public String toggleProduct(
            @PathVariable("id") Long id,
            RedirectAttributes redirect) {

        try {
            vendorService.toggleProduct(currentUserService.require(), id);
            redirect.addFlashAttribute(
                "success",
                "Đã thay đổi trạng thái hiển thị sản phẩm."
            );
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }

        return "redirect:/vendor/products";
    }

    private void prepareProductForm(Model model, Shop shop, Long id) {
        model.addAttribute("shop", shop);
        model.addAttribute("editing", id != null);
        model.addAttribute("productId", id);
        model.addAttribute("categories", vendorService.activeCategories());
        model.addAttribute("statuses", ProductStatus.values());
        model.addAttribute("toppings", productOptionService.availableToppings());
        model.addAttribute("sugarLevels", SugarLevel.values());
        model.addAttribute("iceLevels", IceLevel.values());
    }

    private void refreshAuthentication(
            String username,
            HttpServletRequest request,
            HttpServletResponse response) {

        var details = userDetailsService.loadUserByUsername(username);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            details,
            null,
            details.getAuthorities()
        );

        var previous = SecurityContextHolder.getContext().getAuthentication();

        if (previous != null) {
            authentication.setDetails(previous.getDetails());
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        new HttpSessionSecurityContextRepository()
            .saveContext(context, request, response);
    }
}