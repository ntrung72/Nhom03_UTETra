package vn.iotstar.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.MediaType;

@Service
public class MediaStorageService {

	private static final Logger log = LoggerFactory.getLogger(MediaStorageService.class);

	private static final String PUBLIC_PREFIX = "/uploads/";
	private static final String PRIVATE_PREFIX = "private:";

	private static final Set<String> PUBLIC_FOLDERS = Set.of("avatars", "products", "shops", "categories", "reviews");

	private static final Set<String> PRIVATE_FOLDERS = Set.of("returns", "delivery-proofs");

	private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

	private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mov");

	private static final List<String> REFERENCE_FIELDS = List.of("User.avatarUrl", "Shop.imageUrl", "Category.imageUrl",
			"Product.imageUrl", "ProductImage.imageUrl", "OrderItem.imageUrl", "ReviewMedia.url",
			"ReturnRequestMedia.url", "CustomerOrder.deliveryProofUrl");

	private final MediaValidationService validationService;
	private final Cloudinary cloudinary;
	private final String cloudName;
	private final Path localRoot;
	private final Path privateRoot;

	private EntityManager entityManager;
	private TransactionTemplate referenceTransaction;

	@Autowired
	public MediaStorageService(MediaValidationService validationService,
			@Value("${cloudinary.cloud-name:}") String cloudName, @Value("${cloudinary.api-key:}") String apiKey,
			@Value("${cloudinary.api-secret:}") String apiSecret, @Value("${app.upload-dir:uploads}") String uploadDir,
			@Value("${app.private-upload-dir:private-uploads}") String privateUploadDir) {

		this(validationService, createCloudinary(cloudName, apiKey, apiSecret), cloudName, Path.of(uploadDir),
				Path.of(privateUploadDir));
	}

	// Giữ constructor cũ cho các test đang có.
	public MediaStorageService(MediaValidationService validationService, String cloudName, String apiKey,
			String apiSecret, String uploadDir) {

		this(validationService, createCloudinary(cloudName, apiKey, apiSecret), cloudName, Path.of(uploadDir));
	}

	MediaStorageService(MediaValidationService validationService, Cloudinary cloudinary, String cloudName,
			Path localRoot) {

		this(validationService, cloudinary, cloudName, localRoot,
				localRoot.toAbsolutePath().normalize().resolveSibling("private-uploads"));
	}

	MediaStorageService(MediaValidationService validationService, Cloudinary cloudinary, String cloudName,
			Path localRoot, Path privateRoot) {

		this.validationService = Objects.requireNonNull(validationService);

		this.cloudinary = cloudinary;
		this.cloudName = cloudName == null ? "" : cloudName.trim();
		this.localRoot = localRoot.toAbsolutePath().normalize();
		this.privateRoot = privateRoot.toAbsolutePath().normalize();

		try {
			Path publicPath = canonicalRoot(this.localRoot);
			Path privatePath = canonicalRoot(this.privateRoot);

			if (publicPath.startsWith(privatePath) || privatePath.startsWith(publicPath)) {

				throw new IllegalArgumentException(
						"Thư mục public và private phải tách biệt, " + "không được chứa lẫn nhau.");
			}

		} catch (IOException ex) {
			throw new IllegalArgumentException("Không thể xác định thư mục lưu trữ media.", ex);
		}
	}

	@Autowired(required = false)
	void configureReferenceChecks(EntityManager entityManager, PlatformTransactionManager transactionManager) {

		this.entityManager = entityManager;

		this.referenceTransaction = new TransactionTemplate(transactionManager);

		this.referenceTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		this.referenceTransaction.setReadOnly(true);
	}

	public String upload(MultipartFile file, String folder) {
		validateFolder(folder);

		if (file == null || file.isEmpty()) {
			return null;
		}

		MediaType type = validationService.validate(file, folder);

		String reference;

		if (PRIVATE_FOLDERS.contains(folder)) {
			reference = uploadLocal(file, folder, privateRoot, PRIVATE_PREFIX);

		} else if (cloudinary == null) {
			reference = uploadLocal(file, folder, localRoot, PUBLIC_PREFIX);

		} else {
			reference = uploadCloudinary(file, folder, type);
		}

		try {
			cleanupOnRollback(reference);

		} catch (RuntimeException ex) {
			deleteManaged(reference);
			throw ex;
		}

		return reference;
	}

	/*
	 * Chỉ đọc file. Controller gọi phương thức này phải kiểm tra quyền người dùng.
	 */
	public PrivateMedia readPrivate(String reference, String folder) {

		if (folder == null || !PRIVATE_FOLDERS.contains(folder) || reference == null
				|| !reference.startsWith(PRIVATE_PREFIX + folder + "/")) {

			throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
		}

		Path file = localPath(reference);

		if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {

			throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
		}

		return new PrivateMedia(new FileSystemResource(file), contentType(file.getFileName().toString()));
	}

	public void deleteAfterCommit(String reference) {
		if (reference == null || reference.isBlank()) {
			return;
		}

		if (transactionActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					deleteIfUnreferenced(reference);
				}
			});

		} else {
			deleteIfUnreferenced(reference);
		}
	}

	private void deleteIfUnreferenced(String reference) {
		if (!reference.startsWith(PUBLIC_PREFIX) && !reference.startsWith(PRIVATE_PREFIX)
				&& parseCloudinaryAsset(reference) == null) {
			return;
		}

		try {
			if (referenceTransaction != null) {
				Boolean used = referenceTransaction.execute(status -> isReferenced(reference));

				if (!Boolean.FALSE.equals(used)) {
					return;
				}
			}

			deleteManaged(reference);

		} catch (RuntimeException ex) {
			/*
			 * Kiểm tra thất bại thì giữ file. Không làm lỗi request sau khi dữ liệu đã
			 * commit.
			 */
			log.warn("Không thể kiểm tra/dọn media cũ: {}", reference, ex);
		}
	}

	private boolean isReferenced(String reference) {
		for (String field : REFERENCE_FIELDS) {
			int dot = field.indexOf('.');

			String entity = field.substring(0, dot);
			String attribute = field.substring(dot + 1);

			Long count = entityManager
					.createQuery("select count(e) from " + entity + " e where e." + attribute + " = :reference",
							Long.class)
					.setParameter("reference", reference).getSingleResult();

			if (count == null || count > 0) {
				return true;
			}
		}

		return false;
	}

	private String uploadLocal(MultipartFile file, String folder, Path root, String prefix) {

		Path destination = null;
		boolean created = false;

		try {
			Path directory = root.resolve(folder).normalize();

			Files.createDirectories(directory);

			if (!directory.toRealPath().startsWith(root.toRealPath())) {
				throw new IOException("Thư mục upload vượt khỏi root.");
			}

			String filename = UUID.randomUUID() + "." + extension(file.getOriginalFilename());

			destination = directory.resolve(filename);

			try (InputStream input = file.getInputStream();
					var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
							StandardOpenOption.WRITE)) {
				created = true;
				input.transferTo(output);
			}

			return prefix + folder + "/" + filename;

		} catch (IOException | RuntimeException ex) {
			if (created && destination != null) {
				try {
					Files.deleteIfExists(destination);

				} catch (IOException cleanupError) {
					log.warn("Không thể dọn file upload chưa hoàn tất: {}", destination, cleanupError);
				}
			}

			throw new IllegalStateException("Không thể tải file lên.", ex);
		}
	}

	private String uploadCloudinary(MultipartFile file, String folder, MediaType type) {

		String publicId = "utetra/" + folder + "/" + UUID.randomUUID();

		String resourceType = type == MediaType.VIDEO ? "video" : "image";

		try {
			Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(),
					ObjectUtils.asMap("public_id", publicId, "resource_type", resourceType, "overwrite", false));

			Object value = result == null ? null : result.get("secure_url");

			CloudinaryAsset asset = value instanceof String url ? parseCloudinaryAsset(url) : null;

			if (asset == null || !publicId.equals(asset.publicId()) || !resourceType.equals(asset.resourceType())) {

				throw new IllegalStateException("Cloudinary không trả về URL hợp lệ " + "cho tệp vừa tải lên.");
			}

			return (String) value;

		} catch (IOException | RuntimeException ex) {
			destroyCloudinary(publicId, resourceType);

			throw new IllegalStateException("Không thể tải file lên Cloudinary.", ex);
		}
	}

	private void cleanupOnRollback(String reference) {
		if (!transactionActive()) {
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					deleteManaged(reference);
				}
			}
		});
	}

	private boolean transactionActive() {
		return TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive();
	}

	private void deleteManaged(String reference) {
		CloudinaryAsset asset = parseCloudinaryAsset(reference);

		if (asset != null) {
			destroyCloudinary(asset.publicId(), asset.resourceType());
			return;
		}

		Path file = localPath(reference);

		if (file == null) {
			return;
		}

		try {
			if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {

				Files.deleteIfExists(file);
			}

		} catch (IOException ex) {
			log.warn("Không thể xóa file media: {}", file, ex);
		}
	}

	private Path localPath(String reference) {
		if (reference == null) {
			return null;
		}

		Path root;
		Set<String> folders;
		String relative;

		if (reference.startsWith(PRIVATE_PREFIX)) {
			root = privateRoot;
			folders = PRIVATE_FOLDERS;
			relative = reference.substring(PRIVATE_PREFIX.length());

		} else if (reference.startsWith(PUBLIC_PREFIX)) {
			root = localRoot;
			folders = PUBLIC_FOLDERS;
			relative = reference.substring(PUBLIC_PREFIX.length());

		} else {
			return null;
		}

		String[] parts = relative.split("/", -1);

		if (parts.length != 2 || !folders.contains(parts[0]) || !safeFilename(parts[1])
				|| !supportedExtension(extension(parts[1]))) {
			return null;
		}

		Path directory = root.resolve(parts[0]).normalize();
		Path file = directory.resolve(parts[1]).normalize();

		try {
			if (!file.startsWith(directory) || !directory.toRealPath().startsWith(root.toRealPath())
					|| Files.isSymbolicLink(file)) {
				return null;
			}

			return file;

		} catch (IOException ex) {
			return null;
		}
	}

	private CloudinaryAsset parseCloudinaryAsset(String reference) {
		if (cloudinary == null || reference == null) {
			return null;
		}

		try {
			URI uri = URI.create(reference);

			if (!"https".equalsIgnoreCase(uri.getScheme()) || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())
					|| uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
					|| uri.getRawFragment() != null || uri.getRawPath() == null || uri.getRawPath().contains("%")) {
				return null;
			}

			String[] parts = uri.getRawPath().split("/", -1);

			if (parts.length < 7 || !cloudName.equals(parts[1]) || !Set.of("image", "video").contains(parts[2])
					|| !"upload".equals(parts[3])) {
				return null;
			}

			int index = 4;

			if (parts[index].matches("v[0-9]+")) {
				index++;
			}

			if (parts.length != index + 3 || !"utetra".equals(parts[index])
					|| !PUBLIC_FOLDERS.contains(parts[index + 1])) {
				return null;
			}

			String filename = parts[index + 2];

			if (!safeFilename(filename)) {
				return null;
			}

			int dot = filename.lastIndexOf('.');

			if (dot <= 0) {
				return null;
			}

			Set<String> allowed = "video".equals(parts[2]) ? VIDEO_EXTENSIONS : IMAGE_EXTENSIONS;

			if (!allowed.contains(extension(filename))) {
				return null;
			}

			return new CloudinaryAsset("utetra/" + parts[index + 1] + "/" + filename.substring(0, dot), parts[2]);

		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void destroyCloudinary(String publicId, String resourceType) {

		if (cloudinary == null) {
			return;
		}

		try {
			Map<?, ?> result = cloudinary.uploader().destroy(publicId,
					ObjectUtils.asMap("resource_type", resourceType, "invalidate", true));

			Object outcome = result == null ? null : result.get("result");

			if (!"ok".equals(outcome) && !"not found".equals(outcome)) {

				log.warn("Cloudinary chưa xác nhận xóa asset: {}", publicId);
			}

		} catch (Exception ex) {
			log.warn("Không thể xóa asset Cloudinary: {}", publicId, ex);
		}
	}

	private static Cloudinary createCloudinary(String cloudName, String apiKey, String apiSecret) {

		if (cloudName == null || cloudName.isBlank() || apiKey == null || apiKey.isBlank() || apiSecret == null
				|| apiSecret.isBlank()) {
			return null;
		}

		return new Cloudinary(
				Map.of("cloud_name", cloudName.trim(), "api_key", apiKey, "api_secret", apiSecret, "secure", true));
	}

	private static Path canonicalRoot(Path root) throws IOException {
		Path existing = root;

		while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {

			existing = existing.getParent();
		}

		return existing.toRealPath().resolve(existing.relativize(root)).normalize();
	}

	private void validateFolder(String folder) {
		if (folder == null || (!PUBLIC_FOLDERS.contains(folder) && !PRIVATE_FOLDERS.contains(folder))) {

			throw new IllegalArgumentException("Thư mục tải lên không hợp lệ.");
		}
	}

	private boolean safeFilename(String name) {
		return name != null && !name.isBlank() && !name.contains("..") && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
	}

	private String extension(String filename) {
		if (filename == null) {
			return "";
		}

		int dot = filename.lastIndexOf('.');

		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private boolean supportedExtension(String extension) {
		return IMAGE_EXTENSIONS.contains(extension) || VIDEO_EXTENSIONS.contains(extension);
	}

	private String contentType(String filename) {
		return switch (extension(filename)) {
		case "jpg", "jpeg" -> "image/jpeg";
		case "png" -> "image/png";
		case "webp" -> "image/webp";
		case "mp4" -> "video/mp4";
		case "webm" -> "video/webm";
		case "mov" -> "video/quicktime";

		default -> throw new IllegalArgumentException("Định dạng media không hợp lệ.");
		};
	}

	public record PrivateMedia(Resource resource, String contentType) {
	}

	private record CloudinaryAsset(String publicId, String resourceType) {
	}
}