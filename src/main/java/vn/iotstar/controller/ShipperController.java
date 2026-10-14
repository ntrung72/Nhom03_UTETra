package vn.iotstar.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.User;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.OrderService;

@Controller
@RequestMapping("/shipper")
@RequiredArgsConstructor
public class ShipperController {
    private final CurrentUserService currentUserService;
    private final OrderService orderService;

    @ModelAttribute("currentUser")
    User currentUser() { return currentUserService.optional().orElse(null); }

    @GetMapping
    String dashboard(@RequestParam(required = false) OrderStatus status,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        User shipper = currentUserService.require();
        var orders = orderService.forShipper(shipper, status, page);
        Map<Long, List<OrderStatus>> allowedTransitions = new HashMap<>();
        orders.forEach(order -> allowedTransitions.put(order.getId(), orderService.shipperTransitions(order.getStatus())));
        model.addAttribute("orders", orders);
        model.addAttribute("statuses", List.of(OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP,
            OrderStatus.SHIPPING, OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("allowedTransitions", allowedTransitions);
        model.addAttribute("totalCount", orderService.shipperTotal(shipper));
        model.addAttribute("readyCount", orderService.shipperCount(shipper, OrderStatus.READY_FOR_PICKUP));
        model.addAttribute("pickedCount", orderService.shipperCount(shipper, OrderStatus.PICKED_UP));
        model.addAttribute("shippingCount", orderService.shipperCount(shipper, OrderStatus.SHIPPING));
        model.addAttribute("deliveredCount", orderService.shipperCount(shipper, OrderStatus.DELIVERED));
        model.addAttribute("failedCount", orderService.shipperCount(shipper, OrderStatus.DELIVERY_FAILED));
        return "shipper/dashboard";
    }

    @PostMapping("/orders/{id}/status")
    String status(@PathVariable Long id, @RequestParam OrderStatus status,
                  @RequestParam(required = false) String failureReason,
                  @RequestParam(required = false) MultipartFile deliveryProof,
                  RedirectAttributes redirect) {
        try {
            orderService.shipperUpdate(currentUserService.require(), id, status, failureReason, deliveryProof);
            redirect.addFlashAttribute("success", "Đã cập nhật trạng thái giao hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/shipper";
    }
}
