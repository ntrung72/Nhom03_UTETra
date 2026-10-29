package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.entity.User;
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
    String cart(Model model) {
        var cart = cartService.get(currentUserService.require());
        model.addAttribute("cart", cart);
        model.addAttribute("cartGroups", cart.getItems().stream().collect(Collectors.groupingBy(
            item -> item.getProduct().getShop(), LinkedHashMap::new, Collectors.toList())));
        return "cart/view";
    }

    @PostMapping("/add/{productId}")
    String add(@PathVariable Long productId, @RequestParam(defaultValue = "1") int quantity,
               @RequestParam(required = false) Long sizeId,
               @RequestParam(required = false) SugarLevel sugarLevel,
               @RequestParam(required = false) IceLevel iceLevel,
               @RequestParam(required = false, name = "toppingIds") java.util.List<Long> toppingIds,
               RedirectAttributes redirect) {
        cartService.add(currentUserService.require(), productId, quantity, sizeId, sugarLevel, iceLevel,
            toppingIds == null ? java.util.List.of() : toppingIds);
        redirect.addFlashAttribute("success", "Đã thêm sản phẩm vào giỏ hàng.");
        return "redirect:/cart";
    }

    @PostMapping("/buy-now/{productId}")
    String buyNow(@PathVariable Long productId, @RequestParam(defaultValue = "1") int quantity,
                  @RequestParam(required = false) Long sizeId,
                  @RequestParam(required = false) SugarLevel sugarLevel,
                  @RequestParam(required = false) IceLevel iceLevel,
                  @RequestParam(required = false, name = "toppingIds") java.util.List<Long> toppingIds,
                  RedirectAttributes redirect) {
        try {
            Long itemId = cartService.addForImmediateCheckout(currentUserService.require(), productId,
                quantity, sizeId, sugarLevel, iceLevel,
                toppingIds == null ? java.util.List.of() : toppingIds);
            return "redirect:/checkout?selectionSubmitted=true&itemIds=" + itemId;
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/products/" + productId;
        }
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
}
