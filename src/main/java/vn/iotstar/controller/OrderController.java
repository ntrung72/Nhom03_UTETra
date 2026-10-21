package vn.iotstar.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.service.CartService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProfileService;

@Controller
@RequiredArgsConstructor
public class OrderController {
    private final CurrentUserService currentUserService;
    private final CartService cartService;
    private final ProfileService profileService;
    private final OrderService orderService;
    private final vn.iotstar.service.ReturnRequestService returnRequestService;
    private final vn.iotstar.service.EngagementService engagementService;
    private final vn.iotstar.service.CheckoutPaymentService checkoutPaymentService;
    private final vn.iotstar.service.VnPayService vnPayService;
    private final vn.iotstar.service.MomoService momoService;
    private final vn.iotstar.service.ReorderService reorderService;

    @GetMapping("/checkout")
    String checkout(@ModelAttribute("checkoutForm") CheckoutForm form, BindingResult result,
                    Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) { redirect.addFlashAttribute("error", "Thông tin thanh toán không hợp lệ."); return "redirect:/cart"; }
        if (form.getPaymentMethod() == null) form.setPaymentMethod(vn.iotstar.entity.DomainEnums.PaymentMethod.COD);
        model.addAttribute("vnPayAvailable", vnPayService.isConfigured());
        model.addAttribute("momoAvailable", momoService.isConfigured());
        var user = currentUserService.require();
        var addresses = profileService.addresses(user);
        if (addresses.isEmpty()) {
            redirect.addFlashAttribute("error", "Bạn cần thêm địa chỉ nhận hàng trước.");
            return "redirect:/user/profile";
        }
        try {
            var selection = cartService.prepareCheckoutSelection(user, form.getItemIds());
            form.setItemIds(selection.items().stream().map(i -> i.getId()).toList());
            var carriers = orderService.activeCarriers();
            if (form.getAddressId() == null) form.setAddressId(addresses.getFirst().getId());
            if (form.getCarrierId() == null && !carriers.isEmpty()) form.setCarrierId(carriers.getFirst().getId());
            model.addAttribute("selectedItems", selection.items()); model.addAttribute("addresses", addresses);
            model.addAttribute("carriers", carriers); model.addAttribute("vouchers", orderService.availableVouchers(user));
            model.addAttribute("pricesChanged", selection.pricesChanged());
            if (form.getCarrierId() != null) {
                try { model.addAttribute("voucherRecommendations", orderService.recommendVouchers(user, form.getCarrierId(), form.getItemIds())); }
                catch (IllegalArgumentException ex) { model.addAttribute("error", ex.getMessage()); }
                try { model.addAttribute("preview", orderService.preview(user, form.getCarrierId(), form.getVoucherCode(), form.getItemIds())); }
                catch (IllegalArgumentException ex) { model.addAttribute("error", ex.getMessage()); }
            }
            return "cart/checkout";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage()); return "redirect:/cart";
        }
    }

    @PostMapping("/checkout")
    String checkout(@Valid @ModelAttribute("checkoutForm") CheckoutForm form, BindingResult result,
                    HttpSession session, jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("error", result.getAllErrors().getFirst().getDefaultMessage());
            redirect.addFlashAttribute("checkoutForm", form); return redirectToCheckout(form.getItemIds());
        }
        try {
            var checkout = checkoutPaymentService.checkout(currentUserService.require(), form, request.getRemoteAddr());
            var orders = checkout.orders();
            session.removeAttribute("cartSelection"); session.removeAttribute("cartSelectionOwner");
            if (checkout.paymentUrl() != null) return "redirect:" + checkout.paymentUrl();
            redirect.addFlashAttribute("success", "Đặt hàng COD thành công: " + orders.stream()
                .map(o -> o.getCode()).collect(Collectors.joining(", ")));
            return "redirect:/orders";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage()); redirect.addFlashAttribute("checkoutForm", form);
            return redirectToCheckout(form.getItemIds());
        }
    }

    @GetMapping("/orders")
    String orders(@RequestParam(required = false) vn.iotstar.entity.DomainEnums.OrderStatus status,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("orders", orderService.forUser(currentUserService.require(), status, page));
        model.addAttribute("statuses", vn.iotstar.entity.DomainEnums.OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        return "orders/list";
    }

    @GetMapping("/orders/{id}")
    String detail(@PathVariable Long id, Model model) {
        var user = currentUserService.require();
        model.addAttribute("order", orderService.getForUser(user, id));
        model.addAttribute("statusHistory", orderService.statusHistory(user, id));
        model.addAttribute("returnRequest", returnRequestService.forOrder(user, id));
        model.addAttribute("reviewedItemIds", engagementService.reviewedItemIds(user, id));
        model.addAttribute("paymentTransaction", vnPayService.latestForOrder(user, id));
        model.addAttribute("paymentNeedsReconciliation", vnPayService.needsReconciliation(user, id));
        model.addAttribute("vnPayAvailable", vnPayService.isConfigured());
        model.addAttribute("momoAvailable", momoService.isConfigured());
        return "orders/detail";
    }

    @PostMapping("/orders/{id}/reorder")
    String reorder(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            var result = reorderService.reorder(currentUserService.require(), id);
            if (result.addedLines() > 0) redirect.addFlashAttribute("success", "Đã thêm " + result.addedLines() + " món vào giỏ với giá hiện tại.");
            if (!result.skipped().isEmpty()) redirect.addFlashAttribute("error", String.join("; ", result.skipped()));
            return "redirect:/cart";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage()); return "redirect:/orders/" + id;
        }
    }

    @PostMapping("/orders/{id}/cancel")
    String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            checkoutPaymentService.cancel(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã hủy đơn và hoàn lại tồn kho. Mã giảm giá được hoàn khi tất cả đơn trong checkout đã hủy.");
        } catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/orders/" + id;
    }

    @InitBinder("returnRequestForm")
    void trimReturnDescription(org.springframework.web.bind.WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new org.springframework.beans.propertyeditors.StringTrimmerEditor(true));
    }

    @GetMapping("/orders/{id}/return")
    String returnForm(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        var user = currentUserService.require();
        var order = orderService.getForUser(user, id);
        if (order.getStatus() != vn.iotstar.entity.DomainEnums.OrderStatus.DELIVERED
            || order.getPaymentStatus() != vn.iotstar.entity.DomainEnums.PaymentStatus.PAID
            || returnRequestService.forOrder(user, id) != null) {
            redirect.addFlashAttribute("error", "Chỉ đơn đã giao và thanh toán, chưa có yêu cầu trả hàng mới được mở yêu cầu.");
            return "redirect:/orders/" + id;
        }
        var form = new vn.iotstar.dto.WebForms.ReturnRequestForm(); form.setRefundAmount(order.getTotalAmount());
        prepareReturnForm(model, order, form);
        return "orders/return-form";
    }

    @PostMapping("/orders/{id}/return")
    String requestReturn(@PathVariable Long id,
            @Valid @ModelAttribute("returnRequestForm") vn.iotstar.dto.WebForms.ReturnRequestForm form,
            BindingResult result, @RequestParam(required = false) org.springframework.web.multipart.MultipartFile[] evidence,
            Model model, RedirectAttributes redirect) {
        var user = currentUserService.require();
        var order = orderService.getForUser(user, id);
        if (!result.hasErrors()) {
            try {
                returnRequestService.create(user, id, form, evidence);
                redirect.addFlashAttribute("success", "Đã gửi yêu cầu trả hàng. Shop sẽ xem xét bằng chứng của bạn.");
                return "redirect:/orders/" + id;
            } catch (IllegalArgumentException ex) { result.reject("return.invalid", ex.getMessage()); }
            catch (IllegalStateException ex) { result.reject("return.upload", "Chưa thể lưu bằng chứng. Vui lòng thử lại."); }
        }
        prepareReturnForm(model, order, form);
        return "orders/return-form";
    }

    @PostMapping("/orders/{id}/return/shipped")
    String markReturnShipped(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            returnRequestService.markReturning(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã xác nhận bạn đang gửi hàng trả về shop.");
        } catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/orders/" + id;
    }

    private void prepareReturnForm(Model model, vn.iotstar.entity.CustomerOrder order,
            vn.iotstar.dto.WebForms.ReturnRequestForm form) {
        model.addAttribute("order", order); model.addAttribute("returnRequestForm", form);
        model.addAttribute("returnReasons", vn.iotstar.entity.DomainEnums.ReturnReason.values());
    }

    @InitBinder("reviewForm")
    void trimReview(org.springframework.web.bind.WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new org.springframework.beans.propertyeditors.StringTrimmerEditor(true));
    }

    @PostMapping("/orders/{orderId}/items/{itemId}/review")
    String review(@PathVariable Long orderId, @PathVariable Long itemId,
            @Valid @ModelAttribute("reviewForm") vn.iotstar.dto.WebForms.ReviewForm form, BindingResult result,
            @RequestParam(required = false) org.springframework.web.multipart.MultipartFile[] media, RedirectAttributes redirect) {
        var user = currentUserService.require(); orderService.getForUser(user, orderId);
        if (result.hasErrors()) redirect.addFlashAttribute("error", "Cần 1–5 sao và nhận xét từ 50 đến 3.000 ký tự.");
        else {
            try {
                engagementService.addReview(user, orderId, itemId, form, media);
                redirect.addFlashAttribute("success", "Cảm ơn bạn đã đánh giá sản phẩm.");
            } catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); }
            catch (IllegalStateException ex) { redirect.addFlashAttribute("error", "Chưa thể lưu media đánh giá. Vui lòng thử lại."); }
        }
        return "redirect:/orders/" + orderId;
    }

    @PostMapping("/orders/{id}/payment/retry")
    String retryPayment(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect) {
        try {
            var buyer = currentUserService.require();
            var owned = orderService.getForUser(buyer, id);
            return "redirect:" + (owned.getPaymentMethod() == vn.iotstar.entity.DomainEnums.PaymentMethod.MOMO
                ? momoService.retryPaymentUrl(buyer, id, request.getRemoteAddr())
                : vnPayService.retryPaymentUrl(buyer, id, request.getRemoteAddr()));
        }
        catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); return "redirect:/orders/" + id; }
    }

    @GetMapping("/payments/momo/return")
    String momoReturn(RedirectAttributes redirect) {
        redirect.addFlashAttribute("success", "Đã trở về từ MoMo. Trạng thái đơn được cập nhật sau xác nhận từ cổng thanh toán.");
        return "redirect:/orders";
    }

    @PostMapping("/payments/momo/ipn") @ResponseBody
    org.springframework.http.ResponseEntity<Void> momoIpn(@RequestBody java.util.Map<String, Object> payload) {
        var callback = new java.util.HashMap<String, String>();
        payload.forEach((key, value) -> { if (value != null) callback.put(key, value.toString()); });
        try {
            var result = momoService.processCallback(callback);
            return org.springframework.http.ResponseEntity.status(result.valid() ? 204 : 400).build();
        } catch (RuntimeException ex) { return org.springframework.http.ResponseEntity.status(503).build(); }
    }

    @GetMapping("/payments/vnpay/return")
    String paymentReturn(jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect) {
        try {
            var parameters = paymentParameters(request);
            redirect.addFlashAttribute(vnPayService.returnSuccessful(parameters) ? "success" : "error", vnPayService.returnMessage(parameters));
        }
        catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", "Thông tin phản hồi thanh toán không hợp lệ."); }
        return "redirect:/orders";
    }

    @GetMapping("/payments/vnpay/ipn") @ResponseBody
    java.util.Map<String,String> paymentIpn(jakarta.servlet.http.HttpServletRequest request) {
        try {
            var result = vnPayService.processCallback(paymentParameters(request));
            return java.util.Map.of("RspCode", result.responseCode(), "Message", result.message());
        } catch (IllegalArgumentException ex) { return java.util.Map.of("RspCode", "97", "Message", "Invalid parameters"); }
        catch (RuntimeException ex) { return java.util.Map.of("RspCode", "99", "Message", "Processing error"); }
    }

    private java.util.Map<String,String> paymentParameters(jakarta.servlet.http.HttpServletRequest request) {
        var result = new java.util.TreeMap<String,String>();
        request.getParameterMap().forEach((key, values) -> {
            if (key.startsWith("vnp_")) {
                if (values == null || values.length != 1) throw new IllegalArgumentException("Duplicate payment parameter");
                result.put(key, values[0]);
            }
        });
        return result;
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    void invalidParameter() {}

    private String redirectToCheckout(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "redirect:/cart";
        var builder = UriComponentsBuilder.fromPath("/checkout");
        ids.stream().filter(java.util.Objects::nonNull).forEach(id -> builder.queryParam("itemIds", id));
        return "redirect:" + builder.build().encode().toUriString();
    }
}
