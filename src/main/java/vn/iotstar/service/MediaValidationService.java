package vn.iotstar.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;

@Service
public class MediaValidationService {

    private static final long MB = 1024L * 1024;

    private static final Map<String, List<String>> EXTENSIONS = Map.of(
        "image/jpeg", List.of("jpg", "jpeg"),
        "image/png", List.of("png"),
        "image/webp", List.of("webp"),
        "video/mp4", List.of("mp4"),
        "video/webm", List.of("webm"),
        "video/quicktime", List.of("mov")
    );

    public MediaType validate(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(
                "Vui lòng chọn tệp để tải lên."
            );
        }

        Policy policy = policy(folder);

        String mime = file.getContentType() == null
            ? ""
            : file.getContentType().toLowerCase(Locale.ROOT).trim();

        String filename = file.getOriginalFilename() == null
            ? ""
            : file.getOriginalFilename();

        int dot = filename.lastIndexOf('.');

        String extension = dot < 0
            ? ""
            : filename.substring(dot + 1).toLowerCase(Locale.ROOT);

        List<String> allowedExtensions = EXTENSIONS.get(mime);

        if (allowedExtensions == null
                || !allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(
                "Định dạng tệp không hợp lệ. "
                    + "Ảnh: JPG, PNG, WEBP; video: MP4, WEBM, MOV."
            );
        }

        MediaType type = mime.startsWith("image/")
            ? MediaType.IMAGE
            : MediaType.VIDEO;

        if (type == MediaType.VIDEO && !policy.allowVideo) {
            throw new IllegalArgumentException(
                "Chức năng này chỉ hỗ trợ ảnh JPG, PNG hoặc WEBP."
            );
        }

        long limit = (
            type == MediaType.IMAGE
                ? policy.imageLimitMb
                : policy.videoLimitMb
        ) * MB;

        if (file.getSize() > limit) {
            throw new IllegalArgumentException(
                "Tệp " + filename
                    + " vượt quá " + (limit / MB) + " MB cho "
                    + (type == MediaType.IMAGE ? "ảnh" : "video")
                    + "."
            );
        }

        return type;
    }

    public List<MultipartFile> validateFiles(
            MultipartFile[] uploads,
            String folder) {

        Policy policy = policy(folder);

        if (uploads == null) {
            return List.of();
        }

        List<MultipartFile> files = Arrays.stream(uploads)
            .filter(file -> file != null && !file.isEmpty())
            .toList();

        if (files.size() > policy.maxFiles) {
            throw new IllegalArgumentException(
                "Chỉ được tải lên tối đa "
                    + policy.maxFiles + " tệp."
            );
        }

        files.forEach(file -> validate(file, folder));

        return files;
    }

    private Policy policy(String folder) {
        return switch (folder) {
            case "avatars" ->
                new Policy(5, 0, false, 1);

            case "delivery-proofs" ->
                new Policy(10, 0, false, 1);

            case "products" ->
                new Policy(10, 0, false, 8);

            case "shops", "categories" ->
                new Policy(10, 0, false, 1);

            case "reviews", "returns" ->
                new Policy(10, 25, true, 5);

            default ->
                throw new IllegalArgumentException(
                    "Thư mục tải lên không hợp lệ."
                );
        };
    }

    private record Policy(
        int imageLimitMb,
        int videoLimitMb,
        boolean allowVideo,
        int maxFiles
    ) {
    }
}