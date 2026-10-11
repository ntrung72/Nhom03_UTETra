package vn.iotstar.controller;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.service.CartService;
import vn.iotstar.service.CurrentUserService;

@Controller
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {
    private final CurrentUserService currentUserService;
    private final CartService cartService;

    @GetMapping
    String cart(Model model, HttpSession session) {
        var user = currentUserService.require();
        var view = cartService.view(user);
        var cart = view.cart();
        var eligible = cart.getItems().stream().filter(item -> !view.invalidItemIds().contains(item.getId()))
            .map(item -> item.getId()).toList();
        List<?> stored = user.getId().equals(session.getAttribute("cartSelectionOwner"))
            && session.getAttribute("cartSelection") instanceof List<?> ids ? ids : eligible;
        var selectedIds = eligible.stream().filter(stored::contains).toList();
        model.addAttribute("cart", cart);
        model.addAttribute("cartGroups", cart.getItems().stream().collect(Collectors.groupingBy(
            item -> item.getProduct().getShop(), LinkedHashMap::new, Collectors.toList())));
        model.addAttribute("pricesChanged", view.pricesChanged());
        model.addAttribute("cartWarnings", view.warnings());
        model.addAttribute("invalidItemIds", view.invalidItemIds());
        model.addAttribute("selectedIds", selectedIds);
        model.addAttribute("selectedSubtotal", cart.getItems().stream().filter(item -> selectedIds.contains(item.getId()))
            .map(item -> item.getLineTotal()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        return "cart/view";
    }

    @PostMapping("/add/{productId}")
    String add(@PathVariable Long productId, @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(required = false) Long sizeId,
            @RequestParam(required = false) SugarLevel sugarLevel,
            @RequestParam(required = false) IceLevel iceLevel,
            @RequestParam(required = false) List<Long> toppingIds, RedirectAttributes redirect) {
        cartService.add(currentUserService.require(), productId, quantity, sizeId, sugarLevel, iceLevel, toppingIds);
        redirect.addFlashAttribute("success", "Đã thêm sản phẩm vào giỏ hàng.");
        return "redirect:/cart";
    }

    @PostMapping("/items/{itemId}")
    String update(@PathVariable Long itemId, @RequestParam int quantity, RedirectAttributes redirect) {
        cartService.update(currentUserService.require(), itemId, quantity);
        redirect.addFlashAttribute("success", "Đã cập nhật giỏ hàng.");
        return "redirect:/cart";
    }

    @PostMapping("/items/{itemId}/delete")
    String remove(@PathVariable Long itemId, RedirectAttributes redirect) {
        cartService.remove(currentUserService.require(), itemId);
        redirect.addFlashAttribute("success", "Đã xóa sản phẩm khỏi giỏ.");
        return "redirect:/cart";
    }

    @PostMapping("/selection")
    String select(@RequestParam(required = false) List<Long> itemIds, HttpSession session, RedirectAttributes redirect) {
        var user = currentUserService.require();
        var selection = cartService.prepareSelection(user, itemIds);
        session.setAttribute("cartSelectionOwner", user.getId());
        session.setAttribute("cartSelection", selection.items().stream().map(item -> item.getId()).toList());
        redirect.addFlashAttribute("success", "Đã lưu các sản phẩm được chọn.");
        return "redirect:/cart";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    String invalidCart(IllegalArgumentException ex, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", ex.getMessage());
        return "redirect:/cart";
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    void invalidParameter() {}
}
