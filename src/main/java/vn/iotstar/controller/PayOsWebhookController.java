package vn.iotstar.controller;

import tools.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.iotstar.service.PayOsPaymentService;

@RestController
@RequiredArgsConstructor
public class PayOsWebhookController {
    private final PayOsPaymentService payments;

    @PostMapping("/payments/payos/webhook")
    ResponseEntity<?> webhook(@RequestBody JsonNode body) {
        try {
            payments.receive(body);
            return ResponseEntity.ok().body(java.util.Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("success", false));
        }
    }
}
