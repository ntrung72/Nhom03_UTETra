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

    @GetMapping("/checkout")
    String checkout(@ModelAttribute("checkoutForm") CheckoutForm form, BindingResult result,
                    Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) { redirect.addFlashAttribute("error", "Thông tin thanh toán không hợp lệ."); return "redirect:/cart"; }
        form.setPaymentMethod(vn.iotstar.entity.DomainEnums.PaymentMethod.COD);
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
                    HttpSession session, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("error", result.getAllErrors().getFirst().getDefaultMessage());
            redirect.addFlashAttribute("checkoutForm", form); return redirectToCheckout(form.getItemIds());
        }
        try {
            var orders = orderService.checkout(currentUserService.require(), form);
            session.removeAttribute("cartSelection"); session.removeAttribute("cartSelectionOwner");
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
        return "orders/detail";
    }

    @PostMapping("/orders/{id}/cancel")
    String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            orderService.cancelByUser(currentUserService.require(), id);
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
