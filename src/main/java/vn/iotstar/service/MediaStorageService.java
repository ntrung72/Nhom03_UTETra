package vn.iotstar.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaStorageService {

    private final Cloudinary cloudinary;
    private final Path localRoot;
    private final MediaValidationService validationService;

    public MediaStorageService(
            MediaValidationService validationService,
            @Value("${cloudinary.cloud-name:}") String cloudName,
            @Value("${cloudinary.api-key:}") String apiKey,
            @Value("${cloudinary.api-secret:}") String apiSecret,
            @Value("${app.upload-dir:uploads}") String uploadDir) {

        this.validationService = validationService;
        this.localRoot = Path.of(uploadDir)
            .toAbsolutePath()
            .normalize();

        this.cloudinary =
            cloudName.isBlank()
                || apiKey.isBlank()
                || apiSecret.isBlank()
            ? null
            : new Cloudinary(
                Map.of(
                    "cloud_name", cloudName,
                    "api_key", apiKey,
                    "api_secret", apiSecret,
                    "secure", true
                )
            );
    }

    public String upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        validationService.validate(file, folder);

        try {
            if (cloudinary != null) {
                Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                        "folder", "utetra/" + folder,
                        "resource_type", "auto"
                    )
                );

                return String.valueOf(result.get("secure_url"));
            }

            String original = file.getOriginalFilename() == null
                ? "file"
                : file.getOriginalFilename();

            String extension = original.contains(".")
                ? original.substring(original.lastIndexOf('.'))
                    .replaceAll("[^a-zA-Z0-9.]", "")
                : "";

            String filename = UUID.randomUUID()
                + extension.toLowerCase();

            Path directory = localRoot.resolve(folder).normalize();

            if (!directory.startsWith(localRoot)) {
                throw new IllegalArgumentException(
                    "Thư mục tải lên không hợp lệ."
                );
            }

            Files.createDirectories(directory);

            Files.copy(
                file.getInputStream(),
                directory.resolve(filename),
                StandardCopyOption.REPLACE_EXISTING
            );

            return "/uploads/" + folder + "/" + filename;

        } catch (IOException ex) {
            throw new IllegalStateException(
                "Không thể tải file lên.",
                ex
            );
        }
    }
}