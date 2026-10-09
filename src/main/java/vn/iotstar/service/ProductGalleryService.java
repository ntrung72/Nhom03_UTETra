package vn.iotstar.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductImage;
import vn.iotstar.repository.ProductImageRepository;

@Service
@RequiredArgsConstructor
public class ProductGalleryService {

    private static final int MAX_IMAGES = 8;

    private static final String PLACEHOLDER =
        "/images/product-placeholder.svg";

    private final ProductImageRepository imageRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService validationService;

    @Transactional(readOnly = true)
    public List<ProductImage> images(Product product) {
        return imageRepository
            .findByProductIdOrderBySortOrderAscIdAsc(product.getId());
    }

    @Transactional(readOnly = true)
    public List<ProductImage> displayImages(Product product) {
        return images(product).stream()
            .sorted(
                Comparator.comparing(ProductImage::isPrimary)
                    .reversed()
                    .thenComparingInt(ProductImage::getSortOrder)
                    .thenComparing(ProductImage::getId)
            )
            .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void update(
            Product product,
            MultipartFile cover,
            MultipartFile[] galleryUploads,
            List<Long> removeIds,
            List<Long> orderedIds,
            Long selectedPrimaryId) {

        if (cover != null && !cover.isEmpty()) {
            validationService.validate(cover, "products");
        }

        List<MultipartFile> uploads =
            validationService.validateFiles(
                galleryUploads,
                "products"
            );

        List<ProductImage> existing =
            new ArrayList<>(images(product));

        if (existing.isEmpty()
                && product.getImageUrl() != null
                && !PLACEHOLDER.equals(product.getImageUrl())) {

            ProductImage legacy = image(
                product,
                product.getImageUrl()
            );

            legacy.setPrimary(true);
            legacy.setSortOrder(0);

            existing.add(imageRepository.save(legacy));
        }

        Map<Long, ProductImage> byId = new HashMap<>();

        existing.forEach(
            image -> byId.put(image.getId(), image)
        );

        Set<Long> removals = new HashSet<>(
            removeIds == null ? List.of() : removeIds
        );

        if (!byId.keySet().containsAll(removals)
                || (selectedPrimaryId != null
                    && !byId.containsKey(selectedPrimaryId))
                || (orderedIds != null
                    && !byId.keySet().containsAll(orderedIds))) {

            throw new IllegalArgumentException(
                "Danh sách ảnh sản phẩm không hợp lệ."
            );
        }

        List<ProductImage> remaining = new ArrayList<>();

        if (orderedIds != null) {
            for (Long id : orderedIds) {
                ProductImage image = byId.get(id);

                if (!removals.contains(id)
                        && !remaining.contains(image)) {
                    remaining.add(image);
                }
            }
        }

        existing.stream()
            .filter(
                image -> !removals.contains(image.getId())
                    && !remaining.contains(image)
            )
            .forEach(remaining::add);

        int incomingCount = uploads.size()
            + (cover != null && !cover.isEmpty() ? 1 : 0);

        if (remaining.size() + incomingCount > MAX_IMAGES) {
            throw new IllegalArgumentException(
                "Mỗi sản phẩm có tối đa 8 ảnh. "
                    + "Hãy xóa ảnh cũ trước khi thêm ảnh mới."
            );
        }

        imageRepository.deleteAll(
            existing.stream()
                .filter(
                    image -> removals.contains(image.getId())
                )
                .toList()
        );

        ProductImage newCover = null;

        if (cover != null && !cover.isEmpty()) {
            newCover = image(
                product,
                mediaStorageService.upload(cover, "products")
            );

            remaining.add(newCover);
        }

        for (MultipartFile upload : uploads) {
            remaining.add(
                image(
                    product,
                    mediaStorageService.upload(
                        upload,
                        "products"
                    )
                )
            );
        }

        ProductImage primary = newCover;

        if (primary == null
                && selectedPrimaryId != null
                && !removals.contains(selectedPrimaryId)) {
            primary = byId.get(selectedPrimaryId);
        }

        if (primary == null) {
            primary = remaining.stream()
                .filter(ProductImage::isPrimary)
                .findFirst()
                .orElse(null);
        }

        if (primary == null && !remaining.isEmpty()) {
            primary = remaining.getFirst();
        }

        for (int index = 0; index < remaining.size(); index++) {
            ProductImage image = remaining.get(index);

            image.setSortOrder(index);
            image.setPrimary(image == primary);
        }

        imageRepository.saveAll(remaining);

        product.setImageUrl(
            primary == null
                ? PLACEHOLDER
                : primary.getImageUrl()
        );

        product.setUpdatedAt(LocalDateTime.now());
    }

    private ProductImage image(Product product, String url) {
        ProductImage image = new ProductImage();

        image.setProduct(product);
        image.setImageUrl(url);

        return image;
    }
}