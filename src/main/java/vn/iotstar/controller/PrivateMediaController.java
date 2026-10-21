package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.repository.ReturnRequestMediaRepository;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.OrderService;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequiredArgsConstructor
public class PrivateMediaController {
    private final CurrentUserService currentUserService;
    private final OrderService orderService;
    private final ReturnRequestMediaRepository returnRequestMediaRepository;
    private final MediaStorageService mediaStorageService;

    @GetMapping("/private-media/orders/{orderId}/delivery-proof")
    ResponseEntity<Resource> deliveryProof(@PathVariable Long orderId) {
        try {
            var order = orderService.getForEvidence(currentUserService.require(), orderId);
            if (order.getDeliveryProofUrl() == null) throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
            return serve(mediaStorageService.readPrivate(order.getDeliveryProofUrl(), "delivery-proofs"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy bằng chứng.");
        }
    }

    @GetMapping("/private-media/returns/{mediaId}")
    ResponseEntity<Resource> returnEvidence(@PathVariable Long mediaId) {
        try {
            var media = returnRequestMediaRepository.findDetailedById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bằng chứng."));
            orderService.getForEvidence(currentUserService.require(), media.getReturnRequest().getOrder().getId());
            return serve(mediaStorageService.readPrivate(media.getUrl(), "returns"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(NOT_FOUND, "Không tìm thấy bằng chứng.");
        }
    }

    private ResponseEntity<Resource> serve(MediaStorageService.PrivateMedia media) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType()))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(media.resource());
    }
}
