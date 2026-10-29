package vn.iotstar.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.dto.WebForms.ReviewForm;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ReturnReason;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderItemRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.CheckoutPaymentService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.EngagementService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProfileService;
import vn.iotstar.service.ReorderService;
import vn.iotstar.service.ReturnRequestService;

@Controller
@RequiredArgsConstructor
public class OrderController {
    private final CurrentUserService currentUserService;
    private final CartService cartService;
    private final ProfileService profileService;
    private final OrderService orderService;
    private final EngagementService engagementService;
    private final ReturnRequestService returnRequestService;
    private final CheckoutPaymentService checkoutPaymentService;
    private final ReorderService reorderService;
    private final OrderItemRepository orderItemRepository;

    @GetMapping("/checkout")
    String checkout(@RequestParam(required = false) List<Long> itemIds,
                    @RequestParam(defaultValue = "false") boolean selectionSubmitted,
                    Model model, RedirectAttributes redirect) {
        User user = currentUserService.require();
        if (profileService.addresses(user).isEmpty()) {
            redirect.addFlashAttribute("error", "Bạn cần thêm địa chỉ nhận hàng trước.");
            return "redirect:/user/profile";
        }
        CartService.CartSelection selection;
        try {
            selection = cartService.prepareSelection(user, itemIds, !selectionSubmitted);
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/cart";
        }
        CheckoutForm checkoutForm = new CheckoutForm();
        checkoutForm.setItemIds(selection.items().stream().map(item -> item.getId()).toList());
        model.addAttribute("selectedItems", selection.items());
        model.addAttribute("selectedSubtotal", selection.subtotal());
        model.addAttribute("priceChangedProducts", selection.repricing().changedProducts());
        model.addAttribute("priceChangedNames", String.join(", ", selection.repricing().changedProducts()));
        model.addAttribute("addresses", profileService.addresses(user));
        model.addAttribute("carriers", orderService.activeCarriers());
        model.addAttribute("vouchers", orderService.availableVouchers(user));
        model.addAttribute("checkoutForm", checkoutForm);
        return "cart/checkout";
    }

    @PostMapping("/checkout")
    String checkout(@Valid @ModelAttribute CheckoutForm checkoutForm, BindingResult result,
                    RedirectAttributes redirect) {
        if (result.hasErrors()) {
            if (checkoutForm.getItemIds() == null || checkoutForm.getItemIds().isEmpty()) {
                redirect.addFlashAttribute("error", "Vui lòng chọn ít nhất một sản phẩm để thanh toán.");
                return "redirect:/cart";
            }
            redirect.addFlashAttribute("error", "Vui lòng chọn địa chỉ và nhà vận chuyển.");
            return redirectToCheckout(checkoutForm.getItemIds());
        }
        try {
            var checkoutResult = checkoutPaymentService.checkout(currentUserService.require(), checkoutForm);
            var orders = checkoutResult.orders();
            if (checkoutResult.paymentUrl() != null) return "redirect:" + checkoutResult.paymentUrl();
            String codes = orders.stream().map(order -> order.getCode()).collect(Collectors.joining(", "));
            redirect.addFlashAttribute("success", "Đặt hàng COD thành công: " + codes);
            UriComponentsBuilder resultUrl = UriComponentsBuilder.fromPath("/checkout/result");
            orders.forEach(order -> resultUrl.queryParam("orderIds", order.getId()));
            return "redirect:" + resultUrl.build().encode().toUriString();
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return redirectToCheckout(checkoutForm.getItemIds());
        }
    }

    @GetMapping("/api/checkout/preview")
    @ResponseBody
    ResponseEntity<?> checkoutPreview(@RequestParam Long carrierId,
                                      @RequestParam(required = false) String voucherCode,
                                      @RequestParam List<Long> itemIds) {
        try {
            return ResponseEntity.ok(orderService.preview(currentUserService.require(), carrierId, voucherCode, itemIds));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/api/checkout/vouchers")
    @ResponseBody
    ResponseEntity<?> checkoutVouchers(@RequestParam Long carrierId, @RequestParam List<Long> itemIds) {
        try {
            return ResponseEntity.ok(orderService.recommendVouchers(currentUserService.require(), carrierId, itemIds));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    private String redirectToCheckout(List<Long> itemIds) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/checkout")
            .queryParam("selectionSubmitted", true);
        if (itemIds != null) itemIds.forEach(id -> builder.queryParam("itemIds", id));
        return "redirect:" + builder.build().encode().toUriString();
    }

    @GetMapping("/checkout/result")
    String checkoutResult(@RequestParam(required = false) List<Long> orderIds, Model model) {
        User user = currentUserService.require();
        List<Long> ids = orderIds;
        if (ids == null) ids = List.of();
        if (ids.size() > 20) throw new IllegalArgumentException("Danh sách đơn hàng không hợp lệ.");
        model.addAttribute("orders", ids.stream().distinct()
            .map(id -> orderService.getForUser(user, id)).toList());
        return "cart/result";
    }

    @GetMapping("/orders")
    String orders(@RequestParam(required = false) OrderStatus status,
                  @RequestParam(defaultValue = "0") int page, Model model) {
        var orders = orderService.forUser(currentUserService.require(), status, page);
        var orderIds = orders.getContent().stream().map(CustomerOrder::getId).toList();
        Map<Long, List<OrderItem>> previews = orderIds.isEmpty() ? Map.of() : orderItemRepository.findItemsForOrders(orderIds).stream()
            .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        model.addAttribute("orders", orders);
        model.addAttribute("orderPreviews", previews);
        model.addAttribute("returnRequests", returnRequestService.forOrders(orderIds));
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("selectedStatus", status);
        return "orders/list";
    }

    @GetMapping("/orders/{id}")
    String detail(@PathVariable Long id, Model model) {
        var order = orderService.getForUser(currentUserService.require(), id);
        model.addAttribute("order", order);
        model.addAttribute("cancelBlockReason", orderService.cancellationBlockReason(order));
        model.addAttribute("statusHistory", orderService.statusHistory(order));
        var returnRequest = returnRequestService.forOrder(order.getId());
        model.addAttribute("returnRequest", returnRequest);
        model.addAttribute("hasRefundBank", ReturnRequestService.hasSavedBank(currentUserService.require()));
        model.addAttribute("returnStatusHistory", returnRequest == null ? List.of()
            : returnRequestService.statusHistory(returnRequest.getId()));
        model.addAttribute("paymentTransaction", orderService.displayTransactionForOrder(order.getId()));
        model.addAttribute("reviewForm", new ReviewForm());
        return "orders/detail";
    }

    @PostMapping("/orders/{id}/reorder")
    String reorder(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            var result = reorderService.reorder(currentUserService.require(), id);
            if (!result.skipped().isEmpty()) {
                redirect.addFlashAttribute("reorderWarnings", result.skipped());
            }
            if (result.addedLines() == 0) {
                redirect.addFlashAttribute("error", "Không có món nào còn đủ điều kiện để mua lại.");
                return "redirect:/orders/" + id;
            }
            redirect.addFlashAttribute("success", "Đã thêm " + result.addedLines()
                + " món từ đơn cũ vào giỏ theo giá hiện tại.");
            return "redirect:/cart";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/orders";
        }
    }

    @PostMapping("/orders/{id}/cancel")
    String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            orderService.cancelByUser(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã hủy đơn hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @GetMapping("/orders/{id}/return")
    String returnForm(@PathVariable Long id, Model model) {
        var order = orderService.getForUser(currentUserService.require(), id);
        if (order.getStatus() != OrderStatus.DELIVERED
            || order.getPaymentStatus() != PaymentStatus.PAID
            || returnRequestService.forOrder(id) != null) {
            return "redirect:/orders/" + id;
        }
        ReturnRequestForm form = new ReturnRequestForm();
        form.setRefundAmount(order.getTotalAmount());
        prepareReturnForm(model, order, form);
        return "orders/return-form";
    }

    @PostMapping("/orders/{id}/return")
    String requestReturn(@PathVariable Long id,
                         @Valid @ModelAttribute("returnRequestForm") ReturnRequestForm form,
                         BindingResult result,
                         @RequestParam(required = false) MultipartFile[] evidence,
                         Model model, RedirectAttributes redirect) {
        User user = currentUserService.require();
        var order = orderService.getForUser(user, id);
        if (result.hasErrors()) {
            prepareReturnForm(model, order, form);
            return "orders/return-form";
        }
        try {
            returnRequestService.create(user, id, form, evidence);
        } catch (IllegalArgumentException ex) {
            result.reject("return.invalid", ex.getMessage());
            prepareReturnForm(model, order, form);
            return "orders/return-form";
        }
        redirect.addFlashAttribute("success", "Đã gửi yêu cầu trả hàng. Shop sẽ xem xét bằng chứng của bạn.");
        return "redirect:/orders/" + id;
    }

    @PostMapping("/orders/{id}/return/shipped")
    String markReturnShipped(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            returnRequestService.markReturning(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã báo cửa hàng bạn đã giao sản phẩm trả. Vui lòng chờ cửa hàng xác nhận đã nhận.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/orders/{id}/return/bank")
    String selectReturnBank(@PathVariable Long id, @RequestParam String refundBankAccount,
                            RedirectAttributes redirect) {
        try {
            if (!"SAVED".equals(refundBankAccount)) {
                throw new IllegalArgumentException("Vui lòng chọn tài khoản ngân hàng đã lưu.");
            }
            returnRequestService.selectRefundBank(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã lưu tài khoản nhận tiền hoàn cho yêu cầu này.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/orders/" + id;
    }

    @PostMapping("/orders/{orderId}/items/{itemId}/review")
    String review(@PathVariable Long orderId, @PathVariable Long itemId,
                  @Valid @ModelAttribute ReviewForm reviewForm, BindingResult result,
                  @RequestParam(required = false) MultipartFile[] media,
                  RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("error", "Đánh giá cần 1-5 sao và bình luận tối thiểu 50 ký tự.");
        } else {
            engagementService.addReview(currentUserService.require(), itemId, reviewForm, media);
            redirect.addFlashAttribute("success", "Cảm ơn bạn đã đánh giá sản phẩm.");
        }
        return "redirect:/orders/" + orderId;
    }

    private void prepareReturnForm(Model model, vn.iotstar.entity.CustomerOrder order, ReturnRequestForm form) {
        form.setRefundAmount(order.getTotalAmount());
        model.addAttribute("order", order);
        model.addAttribute("returnRequestForm", form);
        model.addAttribute("returnReasons", ReturnReason.values());
        User user = currentUserService.require();
        model.addAttribute("refundBank", user);
        model.addAttribute("hasRefundBank", ReturnRequestService.hasSavedBank(user));
    }

}
