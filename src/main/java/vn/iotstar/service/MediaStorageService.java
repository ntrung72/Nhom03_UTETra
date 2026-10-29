package vn.iotstar.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;

@Service
public class MediaStorageService {
    private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);
    private static final Set<String> FOLDERS = Set.of("avatars", "products", "shops", "categories",
        "reviews", "returns", "delivery-proofs");
    private static final Set<String> PRIVATE_FOLDERS = Set.of("returns", "delivery-proofs");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mov");

    private final Cloudinary cloudinary;
    private final String cloudName;
    private final Path localRoot;
    private final Path privateRoot;
    private final MediaValidationService validationService;

    @Autowired
    public MediaStorageService(MediaValidationService validationService,
                               @Value("${cloudinary.cloud-name:}") String cloudName,
                               @Value("${cloudinary.api-key:}") String apiKey,
                               @Value("${cloudinary.api-secret:}") String apiSecret,
                               @Value("${app.upload-dir:uploads}") String uploadDir,
                               @Value("${app.private-upload-dir:private-uploads}") String privateUploadDir) {
        this(validationService,
            cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank() ? null
                : new Cloudinary(Map.of("cloud_name", cloudName, "api_key", apiKey, "api_secret", apiSecret, "secure", true)),
            cloudName, Path.of(uploadDir), Path.of(privateUploadDir));
    }

    MediaStorageService(MediaValidationService validationService, Cloudinary cloudinary,
                        String cloudName, Path localRoot) {
        this(validationService, cloudinary, cloudName, localRoot,
            localRoot.toAbsolutePath().normalize().resolveSibling("private-uploads"));
    }

    MediaStorageService(MediaValidationService validationService, Cloudinary cloudinary,
                        String cloudName, Path localRoot, Path privateRoot) {
        this.validationService = validationService;
        this.cloudName = cloudName.trim();
        this.localRoot = localRoot.toAbsolutePath().normalize();
        this.privateRoot = privateRoot.toAbsolutePath().normalize();
        if (this.privateRoot.startsWith(this.localRoot)) {
            throw new IllegalArgumentException("Thư mục bằng chứng riêng tư không được nằm trong thư mục uploads công khai.");
        }
        this.cloudinary = cloudinary;
    }

    public String upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) return null;
        MediaType type = validationService.validate(file, folder);
        String url = PRIVATE_FOLDERS.contains(folder) ? uploadPrivateLocal(file, folder)
            : cloudinary == null ? uploadLocal(file, folder) : uploadCloudinary(file, folder, type);
        cleanupOnRollback(url);
        return url;
    }

    public PrivateMedia readPrivate(String reference, String folder) {
        if (!PRIVATE_FOLDERS.contains(folder) || reference == null) {
            throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
        }
        if (reference.startsWith("private:")) {
            return readLocal(reference.substring("private:".length()), folder, privateRoot);
        }
        if (reference.startsWith("/uploads/")) {
            String relative = reference.substring("/uploads/".length());
            try {
                return readLocal(relative, folder, localRoot);
            } catch (IllegalArgumentException ex) {
                return readLocal(relative, folder, privateRoot);
            }
        }
        CloudinaryAsset asset = parseCloudinaryAsset(reference);
        if (asset == null || !asset.publicId().startsWith("utetra/" + folder + "/")) {
            throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
        }
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(reference)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
                byte[] bytes = body.readNBytes(25 * 1024 * 1024 + 1);
                if (bytes.length > 25 * 1024 * 1024) throw new IllegalArgumentException("Bằng chứng vượt quá dung lượng cho phép.");
                return new PrivateMedia(new ByteArrayResource(bytes), contentType(reference));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể đọc bằng chứng cũ từ Cloudinary.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Không thể đọc bằng chứng cũ từ Cloudinary.", ex);
        }
    }

    public String migrateLegacyPrivateMedia(String reference, String folder) {
        if (reference != null && reference.startsWith("private:")) return reference;
        if (!transactionActive()) throw new IllegalStateException("Chuyển bằng chứng cần giao dịch dữ liệu.");
        PrivateMedia media = readPrivate(reference, folder);
        String extension = reference.substring(reference.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Path directory = privateRoot.resolve(folder).normalize();
        Path destination = directory.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!PRIVATE_FOLDERS.contains(folder) || !destination.startsWith(directory)) {
            throw new IllegalArgumentException("Thư mục bằng chứng không hợp lệ.");
        }
        try {
            Files.createDirectories(directory);
            try (var source = media.resource().getInputStream()) {
                Files.copy(source, destination);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupError) {
                log.warn("Không thể dọn file bằng chứng khi chuyển đổi: {}", destination, cleanupError);
            }
            throw new IllegalStateException("Không thể chuyển bằng chứng vào thư mục riêng tư.", ex);
        }
        String privateReference = "private:" + folder + "/" + destination.getFileName();
        cleanupOnRollback(privateReference);
        deleteAfterCommit(reference);
        return privateReference;
    }

    public void quarantineUnreferencedLegacyFiles() {
        for (String folder : PRIVATE_FOLDERS) {
            Path oldDirectory = localRoot.resolve(folder).normalize();
            if (!Files.isDirectory(oldDirectory)) continue;
            Path destinationDirectory = privateRoot.resolve(folder).normalize();
            try {
                Files.createDirectories(destinationDirectory);
                try (var files = Files.list(oldDirectory)) {
                    files.filter(path -> safeFilename(path.getFileName().toString())
                            && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .forEach(path -> {
                            try {
                                Files.move(path, destinationDirectory.resolve(path.getFileName()));
                            } catch (IOException ex) {
                                log.error("Không thể đưa bằng chứng cũ ra khỏi thư mục public: {}", path, ex);
                            }
                        });
                }
            } catch (IOException ex) {
                log.error("Không thể quét thư mục bằng chứng cũ: {}", oldDirectory, ex);
            }
        }
    }

    private PrivateMedia readLocal(String relative, String folder, Path root) {
        String[] segments = relative.split("/", -1);
        if (segments.length != 2 || !folder.equals(segments[0]) || !safeFilename(segments[1])) {
            throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
        }
        Path directory = root.resolve(folder).normalize();
        Path file = directory.resolve(segments[1]).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
        }
        return new PrivateMedia(new FileSystemResource(file), contentType(segments[1]));
    }

    private String contentType(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> throw new IllegalArgumentException("Định dạng bằng chứng không hợp lệ.");
        };
    }

    public record PrivateMedia(Resource resource, String contentType) {}

    public void deleteAfterCommit(String url) {
        if (url == null || url.isBlank()) return;
        if (transactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteManaged(url);
                }
            });
        } else {
            deleteManaged(url);
        }
    }

    private String uploadCloudinary(MultipartFile file, String folder, MediaType type) {
        String publicId = "utetra/" + folder + "/" + UUID.randomUUID();
        String resourceType = type == MediaType.VIDEO ? "video" : "image";
        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(),
                ObjectUtils.asMap("public_id", publicId, "resource_type", resourceType, "overwrite", false));
            Object secureUrl = result.get("secure_url");
            if (!(secureUrl instanceof String url) || url.isBlank()) {
                throw new IllegalStateException("Cloudinary không trả về URL tệp đã tải lên.");
            }
            return url;
        } catch (IOException | RuntimeException ex) {
            destroyCloudinary(publicId, resourceType);
            throw new IllegalStateException("Không thể tải file lên Cloudinary.", ex);
        }
    }

    private String uploadLocal(MultipartFile file, String folder) {
        return uploadToRoot(file, folder, localRoot, "/uploads/");
    }

    private String uploadPrivateLocal(MultipartFile file, String folder) {
        return uploadToRoot(file, folder, privateRoot, "private:");
    }

    private String uploadToRoot(MultipartFile file, String folder, Path root, String prefix) {
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String extension = original.contains(".")
            ? original.substring(original.lastIndexOf('.')).replaceAll("[^a-zA-Z0-9.]", "") : "";
        String filename = UUID.randomUUID() + extension.toLowerCase(Locale.ROOT);
        Path directory = root.resolve(folder).normalize();
        if (!FOLDERS.contains(folder) || !directory.startsWith(root)) {
            throw new IllegalArgumentException("Thư mục tải lên không hợp lệ.");
        }
        Path destination = directory.resolve(filename);
        try {
            Files.createDirectories(directory);
            Files.copy(file.getInputStream(), destination);
            return prefix + folder + "/" + filename;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupError) {
                log.warn("Không thể dọn file tải lên chưa hoàn tất: {}", destination, cleanupError);
            }
            throw new IllegalStateException("Không thể tải file lên.", ex);
        }
    }

    private void cleanupOnRollback(String url) {
        if (!transactionActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) deleteManaged(url);
            }
        });
    }

    private boolean transactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private void deleteManaged(String url) {
        if (url.startsWith("private:")) {
            deleteLocal(url.substring("private:".length()), privateRoot);
            return;
        }
        if (url.startsWith("/uploads/")) {
            String relative = url.substring("/uploads/".length());
            deleteLocal(relative, localRoot);
            String[] segments = relative.split("/", -1);
            if (segments.length == 2 && PRIVATE_FOLDERS.contains(segments[0])) {
                deleteLocal(relative, privateRoot);
            }
            return;
        }
        CloudinaryAsset asset = parseCloudinaryAsset(url);
        if (asset != null) destroyCloudinary(asset.publicId(), asset.resourceType());
    }

    private void deleteLocal(String relative, Path root) {
        String[] segments = relative.split("/", -1);
        if (segments.length != 2 || !FOLDERS.contains(segments[0]) || !safeFilename(segments[1])) return;
        Path directory = root.resolve(segments[0]).normalize();
        Path path = directory.resolve(segments[1]).normalize();
        if (!path.startsWith(directory)) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Không thể xóa file cũ: {}", path, ex);
        }
    }

    private CloudinaryAsset parseCloudinaryAsset(String url) {
        if (cloudinary == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())) return null;
            String[] segments = uri.getPath().split("/", -1);
            if (segments.length < 7 || !cloudName.equals(segments[1])
                || !("image".equals(segments[2]) || "video".equals(segments[2]))
                || !"upload".equals(segments[3])) return null;
            int folderIndex = 4;
            if (segments[folderIndex].matches("v[0-9]+")) folderIndex++;
            if (segments.length != folderIndex + 3 || !"utetra".equals(segments[folderIndex])
                || !FOLDERS.contains(segments[folderIndex + 1])) return null;
            String filename = segments[folderIndex + 2];
            if (!safeFilename(filename)) return null;
            int dot = filename.lastIndexOf('.');
            if (dot <= 0) return null;
            String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
            Set<String> allowed = "video".equals(segments[2]) ? VIDEO_EXTENSIONS : IMAGE_EXTENSIONS;
            if (!allowed.contains(extension)) return null;
            String publicId = "utetra/" + segments[folderIndex + 1] + "/" + filename.substring(0, dot);
            return new CloudinaryAsset(publicId, segments[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean safeFilename(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
            && name.matches("[A-Za-z0-9._-]+") && !name.contains("..");
    }

    private void destroyCloudinary(String publicId, String resourceType) {
        if (cloudinary == null) return;
        try {
            Map<?, ?> result = cloudinary.uploader().destroy(publicId,
                ObjectUtils.asMap("resource_type", resourceType, "invalidate", true));
            if (!"ok".equals(result.get("result")) && !"not found".equals(result.get("result"))) {
                log.warn("Cloudinary chưa xác nhận xóa file {}: {}", publicId, result);
            }
        } catch (Exception ex) {
            log.warn("Không thể xóa file Cloudinary: {}", publicId, ex);
        }
    }

    private record CloudinaryAsset(String publicId, String resourceType) {}
}
