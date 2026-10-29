package vn.iotstar.controller;

import vn.iotstar.util.VietnamTime;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.dto.WebForms.CarrierForm;
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.service.*;

@Controller
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {
    private final CurrentUserService currentUserService;
    private final ManagementService managementService;
    private final OrderService orderService;
    private final DashboardService dashboardService;
    private final AuditLogService auditLogService;
    private final ReturnRequestService returnRequestService;
    private final ManagementProductService managementProductService;
    private final ProductOptionService productOptionService;
    private final ProductGalleryService productGalleryService;
    private final RevenueReportService revenueReportService;
    private final BankTransferService bankTransferService;
    private final PayOsPaymentService payOsPaymentService;

    @GetMapping("/audit-logs")
    String auditLogs(@RequestParam(required = false) String q,
                     @RequestParam(required = false) AuditAction action,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("logs", auditLogService.search(currentUserService.require(), q, action, page));
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedAction", action);
        return "management/audit-logs";
    }

    @GetMapping("/payments")
    String payments(@RequestParam(required = false) String q,
                    @RequestParam(required = false) PaymentTransactionStatus status,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        var payments = managementService.payments(currentUserService.require(), q, status, page);
        model.addAttribute("payments", payments);
        HashMap<String, BigDecimal> bankTransferTotals = new HashMap<>();
        payments.getContent().stream()
            .filter(payment -> payment.getOrder().getPaymentMethod() == PaymentMethod.BANK_TRANSFER)
            .forEach(payment -> bankTransferTotals.computeIfAbsent(payment.getTransactionCode(),
                bankTransferService::totalForReference));
        model.addAttribute("bankTransferTotals", bankTransferTotals);
        model.addAttribute("reconciliationOrderIds", managementService.paymentReconciliationOrderIds(payments));
        model.addAttribute("statuses", PaymentTransactionStatus.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedStatus", status);
        return "management/payments";
    }

    @PostMapping("/payments/{id}/confirm-bank-transfer")
    String confirmBankTransfer(@PathVariable Long id,
                               @RequestParam String bankTransactionId,
                               @RequestParam BigDecimal receivedAmount,
                               RedirectAttributes redirect) {
        try {
            bankTransferService.confirm(currentUserService.require(), id, bankTransactionId, receivedAmount);
            redirect.addFlashAttribute("success", "Đã xác nhận tiền vào và mở xử lý đơn hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/payments";
    }

    @PostMapping("/payments/{id}/reconcile-payos")
    String reconcilePayOs(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            payOsPaymentService.reconcile(id);
            redirect.addFlashAttribute("success", "Đã kiểm tra lại trạng thái thanh toán với payOS.");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", "Chưa thể kiểm tra với payOS. Vui lòng thử lại sau.");
        }
        return "redirect:/management/payments";
    }

    @PostMapping("/payments/{id}/cancel-unreceived")
    String cancelUnreceivedTransfer(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            bankTransferService.cancelUnreceived(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã đóng đơn chưa nhận tiền và hoàn lại tồn kho.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/payments";
    }

    @GetMapping
    String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                     @RequestParam(required = false) String range,
                     @RequestParam(required = false) Long shopId,
                     @RequestParam(required = false) String status,
                     @RequestParam(required = false) String q,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        User actor = currentUserService.require();
        var dashboardRange = dashboardService.range(range, from, to);
        model.addAttribute("dashboardRange", dashboardRange);
        var stats = managementService.dashboard(actor);
        model.addAttribute("userCount", stats.userCount());
        model.addAttribute("shopCount", stats.shopCount());
        model.addAttribute("productCount", stats.productCount());
        model.addAttribute("orderCount", stats.orderCount());
        model.addAttribute("voucherCount", stats.voucherCount());
        model.addAttribute("platformRevenue", stats.platformRevenue());
        model.addAttribute("sellerRevenue", stats.sellerRevenue());
        model.addAttribute("dashboard", dashboardService.forManagement(actor, shopId, dashboardRange));
        var filter = revenueReportService.filter(dashboardRange.from(), dashboardRange.to(), shopId, status);
        var report = revenueReportService.report(actor, filter);
        model.addAttribute("report", report);
        model.addAttribute("reportRows", revenueReportService.tableRows(report, q, page));
        model.addAttribute("reportQuery", q);
        model.addAttribute("reportOrderUrl", "/management/orders");
        model.addAttribute("reportShops", revenueReportService.availableShops(actor));
        model.addAttribute("reportDashboardUrl", "/management");
        model.addAttribute("reportExportUrl", "/management/reports/revenue.csv");
        model.addAttribute("reportStatuses", OrderStatus.values());
        return "management/dashboard";
    }

    @GetMapping("/users")
    String users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", managementService.users(currentUserService.require(), q, page));
        model.addAttribute("q", q);
        model.addAttribute("roles", Role.values());
        return "management/users";
    }

    @PostMapping("/users/{id}/toggle-lock")
    String toggleUser(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleUser(id, currentUserService.require());
        redirect.addFlashAttribute("success", "Đã đổi trạng thái khóa tài khoản.");
        return "redirect:/management/users";
    }

    @PostMapping("/users/{id}/role")
    String role(@PathVariable Long id, @RequestParam Role role, RedirectAttributes redirect) {
        managementService.changeRole(id, role, currentUserService.require());
        redirect.addFlashAttribute("success", "Đã cập nhật vai trò.");
        return "redirect:/management/users";
    }

    @GetMapping("/shops")
    String shops(@RequestParam(required = false) String q, @RequestParam(required = false) ShopStatus status,
                 @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("shops", managementService.shops(currentUserService.require(), q, status, page));
        model.addAttribute("statuses", ShopStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("q", q);
        return "management/shops";
    }

    @PostMapping("/shops/{id}")
    String updateShop(@PathVariable Long id, @RequestParam ShopStatus status,
                      @RequestParam BigDecimal commissionRate, RedirectAttributes redirect) {
        managementService.updateShop(currentUserService.require(), id, status, commissionRate);
        redirect.addFlashAttribute("success", "Đã cập nhật cửa hàng và chiết khấu ứng dụng.");
        return "redirect:/management/shops";
    }

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
        return "vendor/product-form";
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
            return "vendor/product-form";
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
            return "vendor/product-form";
        }
        try {
            Product saved = managementProductService.save(actor, id, shopId, productForm, image,
                galleryImages, removeImageIds, galleryOrder, primaryImageId);
            redirect.addFlashAttribute("success", "Đã lưu sản phẩm và cấu hình bán hàng.");
            return "redirect:/management/products/" + saved.getId();
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            prepareManagementProductForm(model, productForm, product, shopId);
            return "vendor/product-form";
        }
    }

    @PostMapping("/products/{id}/toggle")
    String toggleProduct(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleProduct(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái sản phẩm.");
        return "redirect:/management/products";
    }

    @GetMapping("/carriers")
    String carriers(@RequestParam(required = false) String q,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("carriers", managementService.carriers(q, page));
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
        Carrier carrier = managementService.getCarrier(id);
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
                       @Valid @ModelAttribute CarrierForm carrierForm, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            if (id != null) model.addAttribute("carrier", managementService.getCarrier(id));
            return "management/carrier-form";
        }
        managementService.saveCarrier(id, carrierForm);
        redirect.addFlashAttribute("success", "Đã lưu nhà vận chuyển.");
        return "redirect:/management/carriers";
    }

    @PostMapping("/carriers/{id}/toggle")
    String toggleCarrier(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleCarrier(id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái nhà vận chuyển.");
        return "redirect:/management/carriers";
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
        form.setStartsAt(VietnamTime.now().withSecond(0).withNano(0));
        form.setEndsAt(VietnamTime.now().plusDays(7).withSecond(0).withNano(0));
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

    @GetMapping("/vouchers")
    String vouchers(@RequestParam(required = false) String q,
                    @RequestParam(required = false) VoucherType type,
                    @RequestParam(required = false) Boolean active,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("vouchers", managementService.vouchers(q, type, active, page));
        model.addAttribute("types", VoucherType.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedActive", active);
        model.addAttribute("now", VietnamTime.now());
        return "management/vouchers";
    }

    @GetMapping("/vouchers/new")
    String newVoucher(Model model) {
        VoucherForm form = new VoucherForm();
        form.setQuantity(100);
        form.setStartsAt(VietnamTime.now().withSecond(0).withNano(0));
        form.setEndsAt(VietnamTime.now().plusMonths(1).withSecond(0).withNano(0));
        prepareVoucher(model, form, null);
        return "management/voucher-form";
    }

    @GetMapping("/vouchers/{id}/edit")
    String editVoucher(@PathVariable Long id, Model model) {
        Voucher voucher = managementService.getVoucher(id);
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
                managementService.saveVoucher(id, voucherForm);
            } catch (IllegalArgumentException ex) {
                result.reject("voucher.invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            prepareVoucher(model, voucherForm, id == null ? null : managementService.getVoucher(id));
            return "management/voucher-form";
        }
        redirect.addFlashAttribute("success", "Đã lưu mã giảm giá.");
        return "redirect:/management/vouchers";
    }

    @PostMapping("/vouchers/{id}/toggle")
    String toggleVoucher(@PathVariable Long id, RedirectAttributes redirect) {
        managementService.toggleVoucher(id);
        redirect.addFlashAttribute("success", "Đã đổi trạng thái mã giảm giá.");
        return "redirect:/management/vouchers";
    }

    @PostMapping("/vouchers/{id}/delete")
    String deleteVoucher(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            managementService.deleteVoucher(id);
            redirect.addFlashAttribute("success", "Đã xóa mã giảm giá.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/vouchers";
    }

    @GetMapping("/vouchers/{id}/usages")
    String voucherUsages(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, Model model) {
        Voucher voucher = managementService.getVoucher(id);
        model.addAttribute("voucher", voucher);
        model.addAttribute("usages", managementService.voucherUsages(id, page));
        return "management/voucher-usages";
    }

    @GetMapping("/orders")
    String orders(@RequestParam(required = false) String q, @RequestParam(required = false) Long shop,
                  @RequestParam(required = false) OrderStatus status,
                  @RequestParam(defaultValue = "false") boolean pending,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        User actor = currentUserService.require();
        var orders = managementService.orders(actor, q, shop, status, pending, page);
        var allowedTransitions = new HashMap<Long, java.util.List<OrderStatus>>();
        var assignableOrders = new HashMap<Long, Boolean>();
        orders.forEach(order -> {
            allowedTransitions.put(order.getId(), order.isAwaitingBankTransferConfirmation()
                ? java.util.List.of() : orderService.managementTransitions(order.getStatus()));
            assignableOrders.put(order.getId(), orderService.canAssignShipper(order.getStatus()));
        });
        model.addAttribute("orders", orders);
        model.addAttribute("allowedTransitions", allowedTransitions);
        model.addAttribute("assignableOrders", assignableOrders);
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("shippers", managementService.shippers());
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("q", q);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pending", pending);
        model.addAttribute("returnRequests", returnRequestService.forOrders(
            orders.getContent().stream().map(CustomerOrder::getId).toList()));
        return "management/orders";
    }

    @PostMapping("/orders/{id}/assign")
    String assign(@PathVariable Long id, @RequestParam Long shipperId, RedirectAttributes redirect) {
        try {
            orderService.assignShipper(currentUserService.require(), id, shipperId);
            redirect.addFlashAttribute("success", "Đã phân công shipper.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @PostMapping("/orders/{id}/retry-delivery")
    String retryDelivery(@PathVariable Long id, @RequestParam Long shipperId, RedirectAttributes redirect) {
        try {
            orderService.retryFailedDelivery(currentUserService.require(), id, shipperId);
            redirect.addFlashAttribute("success", "Đã điều phối giao lại. Chờ shipper chấp nhận phân công.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @PostMapping("/orders/{id}/return-to-shop")
    String confirmReturnToShop(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            orderService.confirmReturnedToShop(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã xác nhận kiện hàng về shop.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
    }

    @PostMapping("/orders/{id}/status")
    String status(@PathVariable Long id, @RequestParam OrderStatus status, RedirectAttributes redirect) {
        try {
            orderService.managementUpdate(currentUserService.require(), id, status);
            redirect.addFlashAttribute("success", "Đã cập nhật trạng thái đơn hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/orders";
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
    String assignManagerShops(@PathVariable Long id,
                              @RequestParam(required = false) java.util.List<Long> shopIds,
                              RedirectAttributes redirect) {
        managementService.assignManagerShops(currentUserService.require(), id, shopIds);
        redirect.addFlashAttribute("success", "Đã cập nhật cửa hàng phụ trách cho Manager.");
        return "redirect:/management/managers";
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
        form.setVersion(product.getVersion());
        form.setStatus(product.getStatus());
        return form;
    }

    private void prepareManagementProductForm(Model model, ProductForm form, Product product, Long selectedShopId) {
        User actor = currentUserService.require();
        var shops = managementService.accessibleShops(actor);
        if (product != null) selectedShopId = product.getShop().getId();
        if (selectedShopId == null && shops.size() == 1) selectedShopId = shops.getFirst().getId();
        model.addAttribute("managementMode", true);
        model.addAttribute("productForm", form);
        model.addAttribute("product", product);
        model.addAttribute("shops", shops);
        model.addAttribute("selectedShopId", selectedShopId);
        model.addAttribute("categories", managementProductService.activeCategories());
        model.addAttribute("toppings", productOptionService.availableToppings());
        model.addAttribute("gallery", product == null ? java.util.List.of() : productGalleryService.images(product));
    }

    private void prepareVoucher(Model model, VoucherForm form, Voucher voucher) {
        model.addAttribute("voucherForm", form);
        model.addAttribute("voucher", voucher);
        model.addAttribute("types", VoucherType.values());
    }
}
