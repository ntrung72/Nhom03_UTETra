package vn.iotstar.controller;

import vn.iotstar.util.VietnamTime;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderItemRepository;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.InventoryService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ReturnRequestService;
import vn.iotstar.service.RevenueReportService;
import vn.iotstar.service.VendorService;

@Controller
@RequestMapping("/vendor")
@RequiredArgsConstructor
public class VendorController {
    private final CurrentUserService currentUserService;
    private final VendorService vendorService;
    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;
    private final ReturnRequestService returnRequestService;
    private final ProductOptionService productOptionService;
    private final DashboardService dashboardService;
    private final InventoryService inventoryService;
    private final ProductGalleryService productGalleryService;
    private final RevenueReportService revenueReportService;

    @GetMapping
    String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) String range,
                     @RequestParam(required = false) Long shopId,
                     @RequestParam(required = false) String status,
                     @RequestParam(required = false) String q,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        Shop shop = vendorService.findShop(user);
        model.addAttribute("shop", shop);
        if (shop != null) {
            var dashboardRange = dashboardService.range(range, from, to);
            model.addAttribute("dashboardRange", dashboardRange);
            var revenue = orderService.revenueSummary(shop);
            model.addAttribute("revenue", revenue.sellerRevenue());
            model.addAttribute("grossRevenue", revenue.grossRevenue());
            model.addAttribute("platformCommission", revenue.platformRevenue());
            model.addAttribute("productCount", vendorService.productCount(shop));
            model.addAttribute("newOrderCount", orderService.shopCount(shop, OrderStatus.NEW));
            model.addAttribute("shippingCount", orderService.shopCount(shop, OrderStatus.SHIPPING));
            model.addAttribute("dashboard", dashboardService.forVendor(shop, dashboardRange));
            var filter = revenueReportService.filter(dashboardRange.from(), dashboardRange.to(), shopId, status);
            var report = revenueReportService.report(user, filter);
            model.addAttribute("report", report);
            model.addAttribute("reportRows", revenueReportService.tableRows(report, q, page));
            model.addAttribute("reportQuery", q);
            model.addAttribute("reportOrderUrl", "/vendor/orders");
            model.addAttribute("reportShops", revenueReportService.availableShops(user));
            model.addAttribute("reportDashboardUrl", "/vendor");
            model.addAttribute("reportExportUrl", "/vendor/reports/revenue.csv");
            model.addAttribute("reportStatuses", OrderStatus.values());
        }
        return "vendor/dashboard";
    }

    @GetMapping("/register")
    String register(Model model) {
        if (vendorService.findShop(currentUserService.require()) != null) return "redirect:/vendor";
        ShopForm form = new ShopForm();
        form.setOpeningTime(LocalTime.of(8, 0));
        form.setClosingTime(LocalTime.of(22, 0));
        model.addAttribute("shopForm", form);
        return "vendor/shop-form";
    }

    @PostMapping("/register")
    String register(@Valid @ModelAttribute ShopForm shopForm, BindingResult result,
                    @RequestParam(required = false) MultipartFile image, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) return "vendor/shop-form";
        vendorService.registerShop(currentUserService.require(), shopForm, image);
        redirect.addFlashAttribute("success", "Đăng ký cửa hàng thành công. Vui lòng chờ Manager/Admin duyệt.");
        return "redirect:/vendor";
    }

    @GetMapping("/shop/edit")
    String editShop(Model model) {
        Shop shop = vendorService.requireShop(currentUserService.require());
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
    String editShop(@Valid @ModelAttribute ShopForm shopForm, BindingResult result,
                    @RequestParam(required = false) MultipartFile image, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("shop", vendorService.requireShop(currentUserService.require()));
            model.addAttribute("editing", true);
            return "vendor/shop-form";
        }
        vendorService.updateShop(currentUserService.require(), shopForm, image);
        redirect.addFlashAttribute("success", "Đã cập nhật trang cửa hàng.");
        return "redirect:/vendor";
    }

    @GetMapping("/products")
    String products(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("products", vendorService.products(currentUserService.require(), q, page));
        model.addAttribute("q", q);
        return "vendor/products";
    }

    @GetMapping("/inventory")
    String inventory(@RequestParam(required = false) Long productId,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        Shop shop = vendorService.requireShop(user);
        model.addAttribute("transactions", inventoryService.forShop(shop, productId, page));
        model.addAttribute("products", vendorService.inventoryProducts(user));
        model.addAttribute("selectedProductId", productId);
        return "vendor/inventory";
    }

    @GetMapping("/products/new")
    String newProduct(Model model) {
        ProductForm form = new ProductForm();
        productOptionService.applyNewProductDefaults(form);
        prepareProductForm(model, form, null);
        return "vendor/product-form";
    }

    @GetMapping("/products/{id}/edit")
    String editProduct(@PathVariable Long id, Model model) {
        Product product = vendorService.productForEdit(currentUserService.require(), id);
        ProductForm form = new ProductForm();
        form.setName(product.getName());
        form.setSku(product.getSku());
        form.setCategoryId(product.getCategory().getId());
        form.setDescription(product.getDescription());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        form.setVersion(product.getVersion());
        form.setStatus(product.getStatus());
        productOptionService.loadConfiguration(product, form);
        prepareProductForm(model, form, product);
        return "vendor/product-form";
    }

    @PostMapping("/products/save")
    String saveProduct(@RequestParam(required = false) Long id,
                       @Valid @ModelAttribute ProductForm productForm, BindingResult result,
                       @RequestParam(required = false) MultipartFile image,
                       @RequestParam(required = false) MultipartFile[] galleryImages,
                       @RequestParam(required = false) List<Long> removeImageIds,
                       @RequestParam(required = false) List<Long> galleryOrder,
                       @RequestParam(required = false) Long primaryImageId,
                       Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            prepareProductForm(model, productForm, id == null ? null : vendorService.productForEdit(currentUserService.require(), id));
            return "vendor/product-form";
        }
        try {
            vendorService.saveProduct(currentUserService.require(), id, productForm, image,
                galleryImages, removeImageIds, galleryOrder, primaryImageId);
            redirect.addFlashAttribute("success", "Đã lưu sản phẩm và cấu hình tùy chọn.");
            return "redirect:/vendor/products";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            prepareProductForm(model, productForm,
                id == null ? null : vendorService.productForEdit(currentUserService.require(), id));
            return "vendor/product-form";
        }
    }

    @PostMapping("/products/{id}/toggle")
    String toggleProduct(@PathVariable Long id, RedirectAttributes redirect) {
        vendorService.toggleProduct(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái sản phẩm.");
        return "redirect:/vendor/products";
    }

    @GetMapping("/orders")
    String orders(@RequestParam(required = false) OrderStatus status,
                  @RequestParam(required = false) String q,
                  @RequestParam(defaultValue = "false") boolean pending,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        Shop shop = vendorService.requireShop(currentUserService.require());
        var orders = orderService.forShop(shop, status, q, pending, page);
        var orderIds = orders.getContent().stream().map(order -> order.getId()).toList();
        Map<Long, List<vn.iotstar.entity.OrderItem>> itemsByOrder = orderIds.isEmpty()
            ? Map.of()
            : orderItemRepository.findItemsForOrders(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(item -> item.getOrder().getId()));
        Map<Long, List<OrderStatus>> allowedTransitions = new HashMap<>();
        orders.forEach(order -> allowedTransitions.put(order.getId(),
            order.isAwaitingBankTransferConfirmation() ? java.util.List.of() : orderService.vendorTransitions(order.getStatus())));
        model.addAttribute("orders", orders);
        model.addAttribute("itemsByOrder", itemsByOrder);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pending", pending);
        model.addAttribute("q", q);
        model.addAttribute("allowedTransitions", allowedTransitions);
        model.addAttribute("returnRequests", returnRequestService.forOrders(
            orders.getContent().stream().map(order -> order.getId()).toList()));
        return "vendor/orders";
    }

    @GetMapping("/orders/{id}")
    String orderDetail(@PathVariable Long id, Model model) {
        var order = orderService.getForShop(currentUserService.require(), id);
        model.addAttribute("order", order);
        return "vendor/order-detail";
    }

    @PostMapping("/orders/{id}/status")
    String updateOrder(@PathVariable Long id, @RequestParam OrderStatus status, RedirectAttributes redirect) {
        try {
            orderService.vendorUpdate(currentUserService.require(), id, status);
            redirect.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/vendor/orders";
    }

    @GetMapping("/returns/{id}")
    String returnDetail(@PathVariable Long id, Model model) {
        var request = returnRequestService.getForShop(currentUserService.require(), id);
        model.addAttribute("returnRequest", request);
        model.addAttribute("returnStatusHistory", returnRequestService.statusHistory(id));
        model.addAttribute("paymentTransaction", orderService.displayTransactionForOrder(request.getOrder().getId()));
        return "vendor/return-detail";
    }

    @PostMapping("/returns/{id}/decision")
    String decideReturn(@PathVariable Long id, @RequestParam ReturnStatus decision,
                        @RequestParam(required = false) java.math.BigDecimal refundAmount,
                        @RequestParam String note, RedirectAttributes redirect) {
        try {
            returnRequestService.decide(currentUserService.require(), id, decision, refundAmount, note);
            redirect.addFlashAttribute("success", "Đã xử lý yêu cầu trả hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/vendor/returns/" + id;
    }

    @PostMapping("/returns/{id}/status")
    String updateReturnStatus(@PathVariable Long id, @RequestParam ReturnStatus status,
                              @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            returnRequestService.vendorTransition(currentUserService.require(), id, status, note);
            redirect.addFlashAttribute("success", "Đã cập nhật quy trình trả hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/vendor/returns/" + id;
    }

    @PostMapping("/returns/{id}/require-return")
    String requirePhysicalReturn(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            returnRequestService.requirePhysicalReturn(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã yêu cầu khách mang sản phẩm trả đến cửa hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/vendor/returns/" + id;
    }

    @PostMapping("/returns/{id}/refund")
    String confirmRefund(@PathVariable Long id, @RequestParam String refundReference,
                         @RequestParam String refundNote, RedirectAttributes redirect) {
        try {
            returnRequestService.confirmRefund(currentUserService.require(), id, refundReference, refundNote);
            redirect.addFlashAttribute("success", "Đã ghi nhận xác nhận hoàn tiền thủ công cho khách.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/vendor/returns/" + id;
    }

    @GetMapping("/promotions")
    String promotions(Model model) {
        model.addAttribute("promotions", vendorService.promotions(currentUserService.require()));
        return "vendor/promotions";
    }

    @GetMapping("/promotions/new")
    String newPromotion(Model model) {
        PromotionForm form = new PromotionForm();
        form.setStartsAt(VietnamTime.now().withSecond(0).withNano(0));
        form.setEndsAt(VietnamTime.now().plusDays(7).withSecond(0).withNano(0));
        preparePromotionForm(model, form, null);
        return "vendor/promotion-form";
    }

    @GetMapping("/promotions/{id}/edit")
    String editPromotion(@PathVariable Long id, Model model) {
        Promotion promotion = vendorService.promotionForEdit(currentUserService.require(), id);
        PromotionForm form = new PromotionForm();
        form.setName(promotion.getName());
        form.setType(promotion.getType());
        form.setDiscountValue(promotion.getDiscountValue());
        form.setProductId(promotion.getProduct() == null ? null : promotion.getProduct().getId());
        form.setStartsAt(promotion.getStartsAt());
        form.setEndsAt(promotion.getEndsAt());
        form.setActive(promotion.isActive());
        preparePromotionForm(model, form, promotion);
        return "vendor/promotion-form";
    }

    @PostMapping("/promotions/save")
    String savePromotion(@RequestParam(required = false) Long id,
                         @Valid @ModelAttribute PromotionForm promotionForm, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (!result.hasErrors()) {
            try {
                vendorService.savePromotion(currentUserService.require(), id, promotionForm);
            } catch (IllegalArgumentException ex) {
                result.reject("promotion.invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            preparePromotionForm(model, promotionForm, id == null ? null : vendorService.promotionForEdit(currentUserService.require(), id));
            return "vendor/promotion-form";
        }
        redirect.addFlashAttribute("success", "Đã lưu chương trình khuyến mãi.");
        return "redirect:/vendor/promotions";
    }

    @PostMapping("/promotions/{id}/delete")
    String deletePromotion(@PathVariable Long id, RedirectAttributes redirect) {
        vendorService.deletePromotion(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã xóa chương trình khuyến mãi.");
        return "redirect:/vendor/promotions";
    }

    private void prepareProductForm(Model model, ProductForm form, Product product) {
        model.addAttribute("productForm", form);
        model.addAttribute("product", product);
        model.addAttribute("categories", vendorService.activeCategories());
        model.addAttribute("toppings", productOptionService.availableToppings());
        model.addAttribute("gallery", product == null ? List.of() : productGalleryService.images(product));
    }

    private void preparePromotionForm(Model model, PromotionForm form, Promotion promotion) {
        model.addAttribute("promotionForm", form);
        model.addAttribute("promotion", promotion);
        model.addAttribute("products", vendorService.promotionProducts(currentUserService.require()));
    }
}
