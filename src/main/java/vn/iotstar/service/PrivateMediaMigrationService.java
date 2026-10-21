package vn.iotstar.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ReturnRequestMediaRepository;

/**
 * Migrates old public-local evidence references to a private store without changing TV2's
 * MediaStorageService. Legacy non-local references are preserved for manual migration.
 * Requests for the old public folder are denied by WebConfig and SecurityConfig.
 */
@Service
@Profile("!test")
@RequiredArgsConstructor
public class PrivateMediaMigrationService {
    private static final Logger log = LoggerFactory.getLogger(PrivateMediaMigrationService.class);
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "mp4", "webm", "mov");
    private final OrderRepository orderRepository;
    private final ReturnRequestMediaRepository mediaRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.upload-dir:uploads}") private String uploadDir;
    @Value("${app.private-upload-dir:private-uploads}") private String privateUploadDir;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyEvidence() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        for (Long orderId : orderRepository.findLegacyDeliveryProofOrderIds()) {
            try {
                transactions.executeWithoutResult(status -> {
                    var order = orderRepository.findById(orderId).orElseThrow();
                    order.setDeliveryProofUrl(copyLocalLegacy(order.getDeliveryProofUrl(), "delivery-proofs"));
                });
            } catch (RuntimeException ex) {
                log.warn("Chưa thể chuyển bằng chứng giao hàng cho đơn {} (không thay đổi bản gốc).", orderId, ex);
            }
        }
        for (Long mediaId : mediaRepository.findLegacyEvidenceIds()) {
            try {
                transactions.executeWithoutResult(status -> {
                    var media = mediaRepository.findById(mediaId).orElseThrow();
                    media.setUrl(copyLocalLegacy(media.getUrl(), "returns"));
                });
            } catch (RuntimeException ex) {
                log.warn("Chưa thể chuyển bằng chứng trả hàng {} (không thay đổi bản gốc).", mediaId, ex);
            }
        }
    }

    String copyLocalLegacy(String reference, String folder) {
        if (reference != null && reference.startsWith("private:" + folder + "/")) return reference;
        if (reference == null || !Set.of("returns", "delivery-proofs").contains(folder)
            || !reference.startsWith("/uploads/" + folder + "/")) {
            throw new IllegalArgumentException("Bằng chứng cũ không thuộc đường dẫn local được hỗ trợ.");
        }
        String filename = reference.substring(("/uploads/" + folder + "/").length());
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || filename.contains("..")) {
            throw new IllegalArgumentException("Tên tệp bằng chứng cũ không hợp lệ.");
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 1 || !EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Định dạng bằng chứng cũ không hợp lệ.");
        }
        Path root = Path.of(uploadDir).toAbsolutePath().normalize();
        Path privateRoot = Path.of(privateUploadDir).toAbsolutePath().normalize();
        Path oldDir = root.resolve(folder);
        Path original = oldDir.resolve(filename);
        Path destinationDir = privateRoot.resolve(folder);
        try {
            if (!Files.isDirectory(oldDir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !oldDir.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Không tìm thấy bằng chứng local hợp lệ.");
            }
            Files.createDirectories(destinationDir);
            if (Files.isSymbolicLink(destinationDir) || !destinationDir.toRealPath().startsWith(privateRoot.toRealPath())) {
                throw new IllegalArgumentException("Thư mục private không hợp lệ.");
            }
            String newName = UUID.randomUUID() + filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
            Path destination = destinationDir.resolve(newName);
            Files.copy(original, destination);  // Never move/delete a source during a database transaction.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            try { Files.deleteIfExists(destination); }
                            catch (IOException e) { log.warn("Không thể dọn file migration rollback: {}", destination, e); }
                        }
                    }
                });
            return "private:" + folder + "/" + newName;
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể chuyển bằng chứng cũ sang private.", ex);
        }
    }
}
