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

    @GetMapping
    String dashboard(@RequestParam(required = false) OrderStatus status,
                     @RequestParam(required = false) String q,
                     @RequestParam(defaultValue = "recent") String sort,
                     @RequestParam(defaultValue = "0") int page, Model model) {
        User shipper = currentUserService.require();
        String selectedSort = "oldest".equals(sort) ? "oldest" : "recent";
        var orders = orderService.forShipper(shipper, status, q, page, selectedSort);
        Map<Long, List<OrderStatus>> allowedTransitions = new HashMap<>();
        orders.forEach(order -> allowedTransitions.put(order.getId(), orderService.shipperTransitions(order.getStatus())));
        model.addAttribute("orders", orders);
        model.addAttribute("statuses", List.of(OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP,
            OrderStatus.SHIPPING, OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED));
        model.addAttribute("selectedStatus", status);
        model.addAttribute("q", q == null ? "" : q.strip());
        model.addAttribute("selectedSort", selectedSort);
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
                  @RequestParam(defaultValue = "false") boolean codCollected,
                  RedirectAttributes redirect) {
        try {
            orderService.shipperUpdate(currentUserService.require(), id, status, failureReason, deliveryProof, codCollected);
            redirect.addFlashAttribute("success", "Đã cập nhật trạng thái giao hàng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/shipper";
    }

    @PostMapping("/orders/{id}/return-to-shop")
    String returnToShop(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            orderService.confirmReturnedToShop(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã xác nhận kiện hàng về shop.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/shipper";
    }

    @PostMapping("/orders/{id}/assignment")
    String assignment(@PathVariable Long id, @RequestParam String decision,
                      @RequestParam(required = false) String rejectionReason,
                      RedirectAttributes redirect) {
        try {
            if (!"accept".equals(decision) && !"reject".equals(decision)) {
                throw new IllegalArgumentException("Phản hồi phân công không hợp lệ.");
            }
            boolean accept = "accept".equals(decision);
            orderService.respondToShipperAssignment(currentUserService.require(), id, accept, rejectionReason);
            redirect.addFlashAttribute("success", accept ? "Đã chấp nhận đơn giao."
                : "Đã từ chối đơn. Quản lý sẽ phân công lại.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/shipper";
    }
}
