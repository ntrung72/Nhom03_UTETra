package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.service.BankTransferService;
import vn.iotstar.service.BankTransferService.BankTransferDetails;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.PayOsPaymentService;

@Controller
@RequestMapping("/bank-transfer")
@RequiredArgsConstructor
public class BankTransferController {
    private static final Logger log = LoggerFactory.getLogger(BankTransferController.class);
    private final BankTransferService bankTransferService;
    private final CurrentUserService currentUserService;
    private final PayOsPaymentService payOsPaymentService;

    @GetMapping("/checkout/{checkoutReference}")
    String checkout(@PathVariable String checkoutReference, Model model) {
        model.addAttribute("transfers", bankTransferService.checkoutDetails(
            currentUserService.require(), checkoutReference).stream().map(this::refreshPayOs).toList());
        model.addAttribute("checkoutReference", checkoutReference);
        return "cart/bank-transfer-checkout";
    }

    @GetMapping("/{reference}")
    String payment(@PathVariable String reference, Model model) {
        model.addAttribute("transfer", refreshPayOs(bankTransferService.details(currentUserService.require(), reference)));
        return "cart/bank-transfer";
    }

    private BankTransferDetails refreshPayOs(BankTransferDetails transfer) {
        if (transfer.payOsUrl() == null || !transfer.payable() || transfer.paid()) return transfer;
        try {
            payOsPaymentService.reconcile(transfer.paymentId());
        } catch (RuntimeException ex) {
            log.warn("Khong the kiem tra lai payOS cho giao dich #{}", transfer.paymentId(), ex);
        }
        return bankTransferService.details(currentUserService.require(), transfer.reference());
    }

    @GetMapping(value = "/{reference}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(@PathVariable String reference) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_PNG)
            .body(bankTransferService.qrImage(currentUserService.require(), reference));
    }

    @PostMapping("/{reference}/sent")
    String sent(@PathVariable String reference,
                @RequestParam(required = false) String checkoutReference,
                RedirectAttributes redirect) {
        try {
            bankTransferService.markSent(currentUserService.require(), reference);
            redirect.addFlashAttribute("success", "Đã ghi nhận thông báo của bạn. Admin sẽ kiểm tra tiền vào tài khoản trước khi xác nhận đơn.");
            if (checkoutReference != null && checkoutReference.equals(bankTransferService
                .details(currentUserService.require(), reference).orders().getFirst().getCheckoutReference())) {
                return "redirect:/bank-transfer/checkout/" + checkoutReference;
            }
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/bank-transfer/" + reference;
    }
}
