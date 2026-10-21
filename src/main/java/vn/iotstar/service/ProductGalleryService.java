package vn.iotstar.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductImage;
import vn.iotstar.repository.ProductImageRepository;

@Service
public class ProductGalleryService {

	private static final int MAX_IMAGES = 8;

	private static final String PLACEHOLDER = "/images/product-placeholder.svg";

	private final ProductImageRepository imageRepository;
	private final MediaStorageService mediaStorageService;
	private final MediaValidationService validationService;

	public ProductGalleryService(ProductImageRepository imageRepository, MediaStorageService mediaStorageService,
			MediaValidationService validationService) {

		this.imageRepository = imageRepository;
		this.mediaStorageService = mediaStorageService;
		this.validationService = validationService;
	}

	@Transactional(readOnly = true)
	public List<ProductImage> images(Product product) {
		requirePersistedProduct(product);

		return imageRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId());
	}

	@Transactional(readOnly = true)
	public List<ProductImage> displayImages(Product product) {
		return images(product).stream().sorted(Comparator.comparing(ProductImage::isPrimary).reversed()
				.thenComparingInt(ProductImage::getSortOrder).thenComparing(ProductImage::getId)).toList();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void update(Product product, MultipartFile cover, MultipartFile[] galleryUploads, List<Long> removeIds,
			List<Long> orderedIds, Long selectedPrimaryId) {

		requirePersistedProduct(product);

		boolean hasCover = cover != null && !cover.isEmpty();

		if (hasCover) {
			validationService.validate(cover, "products");
		}

		List<MultipartFile> uploads = validationService.validateFiles(galleryUploads, "products");

		List<ProductImage> existing = new ArrayList<>(images(product));

		Map<Long, ProductImage> byId = new HashMap<>();

		existing.forEach(image -> byId.put(image.getId(), image));

		Set<Long> removals = checkedIds(removeIds, byId);
		Set<Long> ordering = checkedIds(orderedIds, byId);

		if (selectedPrimaryId != null && !byId.containsKey(selectedPrimaryId)) {

			throw new IllegalArgumentException("Ảnh bìa được chọn không thuộc sản phẩm này.");
		}

		List<ProductImage> remaining = new ArrayList<>();
		Set<Long> addedIds = new HashSet<>();

		// Giữ thứ tự được gửi lên; ID lặp chỉ được thêm một lần.
		for (Long id : ordering) {
			if (!removals.contains(id)) {
				remaining.add(byId.get(id));
				addedIds.add(id);
			}
		}

		// Các ảnh không có trong danh sách sắp xếp vẫn được giữ lại.
		for (ProductImage image : existing) {
			if (!removals.contains(image.getId()) && addedIds.add(image.getId())) {

				remaining.add(image);
			}
		}

		String previousCover = product.getImageUrl();

		/*
		 * Chuyển ảnh bìa cũ thành gallery. Chưa ghi DB trong giai đoạn kiểm tra.
		 */
		if (existing.isEmpty() && hasImage(previousCover)) {
			ProductImage legacy = image(product, previousCover);
			legacy.setPrimary(true);
			remaining.add(legacy);
		}

		int incomingCount = uploads.size() + (hasCover ? 1 : 0);

		if (remaining.size() + incomingCount > MAX_IMAGES) {
			throw new IllegalArgumentException(
					"Mỗi sản phẩm có tối đa 8 ảnh, bao gồm ảnh bìa. " + "Hãy xóa ảnh cũ trước khi thêm ảnh mới.");
		}

		/*
		 * Upload trước khi xóa/sửa các dòng ảnh cũ. Nếu upload thất bại, giao dịch
		 * rollback. MediaStorageService sẽ dọn file mới đã upload thành công trong giao
		 * dịch đó.
		 */
		ProductImage newCover = null;

		if (hasCover) {
			newCover = uploadImage(product, cover);
			remaining.add(newCover);
		}

		for (MultipartFile upload : uploads) {
			remaining.add(uploadImage(product, upload));
		}

		// Ảnh bìa mới được ưu tiên.
		ProductImage primary = newCover;

		if (primary == null && selectedPrimaryId != null && !removals.contains(selectedPrimaryId)) {

			primary = byId.get(selectedPrimaryId);
		}

		if (primary == null) {
			primary = remaining.stream().filter(ProductImage::isPrimary).findFirst().orElse(null);
		}

		if (primary == null && !remaining.isEmpty()) {
			primary = remaining.get(0);
		}

		// Chuẩn hóa thứ tự và bảo đảm chỉ có một ảnh bìa.
		for (int index = 0; index < remaining.size(); index++) {
			ProductImage image = remaining.get(index);

			image.setSortOrder(index);
			image.setPrimary(image == primary);
		}

		List<ProductImage> removed = existing.stream().filter(image -> removals.contains(image.getId())).toList();

		imageRepository.deleteAll(removed);
		imageRepository.saveAll(remaining);

		product.setImageUrl(primary == null ? PLACEHOLDER : primary.getImageUrl());

		product.setUpdatedAt(LocalDateTime.now());

		Set<String> retainedUrls = remaining.stream().map(ProductImage::getImageUrl).collect(Collectors.toSet());

		Set<String> cleanupCandidates = new LinkedHashSet<>();

		removed.stream().map(ProductImage::getImageUrl).filter(this::hasImage).forEach(cleanupCandidates::add);

		if (hasImage(previousCover)) {
			cleanupCandidates.add(previousCover);
		}

		/*
		 * Không yêu cầu xóa URL vẫn còn trong gallery. MediaStorageService tiếp tục
		 * kiểm tra tham chiếu trong DB trước khi xóa file sau commit.
		 */
		cleanupCandidates.stream().filter(url -> !retainedUrls.contains(url))
				.forEach(mediaStorageService::deleteAfterCommit);
	}

	private Set<Long> checkedIds(List<Long> ids, Map<Long, ProductImage> existing) {

		Set<Long> result = new LinkedHashSet<>();

		if (ids == null) {
			return result;
		}

		for (Long id : ids) {
			if (id == null || !existing.containsKey(id)) {
				throw new IllegalArgumentException("Danh sách ảnh chứa ảnh không thuộc sản phẩm này.");
			}

			result.add(id);
		}

		return result;
	}

	private ProductImage uploadImage(Product product, MultipartFile file) {

		String url = mediaStorageService.upload(file, "products");

		if (url == null || url.isBlank()) {
			throw new IllegalStateException("Không nhận được đường dẫn ảnh sau khi tải lên.");
		}

		return image(product, url);
	}

	private ProductImage image(Product product, String url) {
		ProductImage image = new ProductImage();

		image.setProduct(product);
		image.setImageUrl(url);

		return image;
	}

	private boolean hasImage(String url) {
		return url != null && !url.isBlank() && !PLACEHOLDER.equals(url);
	}

	private void requirePersistedProduct(Product product) {
		if (product == null || product.getId() == null) {
			throw new IllegalArgumentException("Sản phẩm phải được lưu trước khi cập nhật gallery.");
		}
	}
}