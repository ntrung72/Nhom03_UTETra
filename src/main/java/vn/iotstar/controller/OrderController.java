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
            return "redirect:/cart";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage()); redirect.addFlashAttribute("checkoutForm", form);
            return redirectToCheckout(form.getItemIds());
        }
    }

    private String redirectToCheckout(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "redirect:/cart";
        var builder = UriComponentsBuilder.fromPath("/checkout");
        ids.stream().filter(java.util.Objects::nonNull).forEach(id -> builder.queryParam("itemIds", id));
        return "redirect:" + builder.build().encode().toUriString();
    }
}
