package vn.iotstar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.ProductRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    private final InventoryTransactionRepository repository;
    private final ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public Product lockProduct(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                "Mã sản phẩm không hợp lệ."
            );
        }

        Product product = productRepository
            .findByIdForUpdate(productId)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "Không tìm thấy sản phẩm."
                )
            );

        entityManager.refresh(
            product,
            LockModeType.PESSIMISTIC_WRITE
        );

        return product;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(
            Product product,
            int initialStock,
            User actor) {

        if (product == null || product.getStock() != 0) {
            throw new IllegalArgumentException(
                "Sản phẩm mới phải có tồn kho ban đầu bằng 0."
            );
        }

        changeStock(
            product,
            initialStock,
            InventoryTransactionType.INITIAL,
            actor,
            null,
            "Nhập kho khi tạo sản phẩm"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setStock(
            Product product,
            int targetStock,
            User actor) {

        if (product == null) {
            throw new IllegalArgumentException(
                "Sản phẩm không hợp lệ."
            );
        }

        InventoryTransactionType type =
            targetStock > product.getStock()
                ? InventoryTransactionType.RESTOCK
                : InventoryTransactionType.ADJUSTMENT;

        changeStock(
            product,
            targetStock,
            type,
            actor,
            null,
            "Cập nhật tồn kho từ trang sửa sản phẩm"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sell(
            Product product,
            int quantity,
            User actor,
            CustomerOrder order) {

        requirePositiveQuantity(quantity);
        requireOrder(order);

        if (product == null
                || product.getStock() < quantity) {
            throw new IllegalArgumentException(
                "Sản phẩm không đủ tồn kho."
            );
        }

        changeStock(
            product,
            product.getStock() - quantity,
            InventoryTransactionType.SALE,
            actor,
            order,
            "Trừ kho khi khách hàng đặt đơn"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreAfterCancellation(
            Product product,
            int quantity,
            User actor,
            CustomerOrder order) {

        requirePositiveQuantity(quantity);
        requireOrder(order);

        if (product == null) {
            throw new IllegalArgumentException(
                "Sản phẩm không hợp lệ."
            );
        }

        long restored = (long) product.getStock() + quantity;

        if (restored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Tồn kho vượt giới hạn cho phép."
            );
        }

        changeStock(
            product,
            (int) restored,
            InventoryTransactionType.CANCELLATION,
            actor,
            order,
            "Hoàn kho khi khách hàng hủy đơn"
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            Product product,
            int before,
            int after,
            InventoryTransactionType type,
            User actor,
            CustomerOrder order,
            String note) {

        if (product == null
                || product.getId() == null
                || product.getShop() == null
                || product.getShop().getId() == null
                || actor == null
                || actor.getId() == null
                || type == null
                || before < 0
                || after < 0) {
            throw new IllegalArgumentException(
                "Biến động kho không hợp lệ."
            );
        }

        if (order != null
                && (order.getId() == null
                    || order.getShop() == null
                    || !product.getShop().getId().equals(
                        order.getShop().getId()
                    ))) {
            throw new IllegalArgumentException(
                "Đơn hàng không thuộc cửa hàng của sản phẩm."
            );
        }

        String cleanedNote = note == null
            ? null
            : note.trim();

        if (cleanedNote != null
                && cleanedNote.length() > 255) {
            throw new IllegalArgumentException(
                "Ghi chú kho không được vượt quá 255 ký tự."
            );
        }

        if (before == after
                && type != InventoryTransactionType.INITIAL) {
            return;
        }

        InventoryTransaction transaction =
            new InventoryTransaction();

        transaction.setShop(product.getShop());
        transaction.setProduct(product);
        transaction.setProductName(product.getName());
        transaction.setProductSku(product.getSku());
        transaction.setOrder(order);
        transaction.setActor(actor);
        transaction.setType(type);
        transaction.setQuantityChange(after - before);
        transaction.setStockBefore(before);
        transaction.setStockAfter(after);
        transaction.setNote(cleanedNote);

        repository.save(transaction);
    }

    public Page<InventoryTransaction> forShop(
            Shop shop,
            Long productId,
            int page) {

        if (shop == null || shop.getId() == null) {
            throw new IllegalArgumentException(
                "Cửa hàng không hợp lệ."
            );
        }

        if (productId != null && productId <= 0) {
            throw new IllegalArgumentException(
                "Mã sản phẩm không hợp lệ."
            );
        }

        PageRequest pageable = PageRequest.of(
            Math.max(0, page),
            20
        );

        return productId == null
            ? repository.findByShopIdOrderByCreatedAtDescIdDesc(
                shop.getId(),
                pageable
            )
            : repository
                .findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(
                    shop.getId(),
                    productId,
                    pageable
                );
    }

    private void changeStock(
            Product product,
            int after,
            InventoryTransactionType type,
            User actor,
            CustomerOrder order,
            String note) {

        if (product == null || after < 0) {
            throw new IllegalArgumentException(
                "Tồn kho không được âm."
            );
        }

        if (type != InventoryTransactionType.INITIAL) {
            requireProductLock(product);
        }

        int before = product.getStock();

        record(
            product,
            before,
            after,
            type,
            actor,
            order,
            note
        );

        product.setStock(after);

        if (after == 0
                && product.getStatus() == ProductStatus.ACTIVE) {
            product.setStatus(ProductStatus.OUT_OF_STOCK);
        } else if (after > 0
                && product.getStatus()
                    == ProductStatus.OUT_OF_STOCK) {
            product.setStatus(ProductStatus.ACTIVE);
        }

        // Ghi tồn kho và lịch sử xuống database trong cùng giao dịch.
        // Tránh refresh() sau đó tải lại số lượng chưa được cập nhật.
        entityManager.flush();
    }

    private void requireProductLock(Product product) {
        if (!entityManager.contains(product)) {
            throw new IllegalStateException(
                "Sản phẩm phải thuộc giao dịch hiện tại."
            );
        }

        LockModeType lockMode =
            entityManager.getLockMode(product);

        if (lockMode != LockModeType.PESSIMISTIC_WRITE
                && lockMode
                    != LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
            throw new IllegalStateException(
                "Phải khóa sản phẩm trước khi thay đổi tồn kho."
            );
        }
    }

    private void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                "Số lượng phải lớn hơn 0."
            );
        }
    }

    private void requireOrder(CustomerOrder order) {
        if (order == null || order.getId() == null) {
            throw new IllegalArgumentException(
                "Đơn hàng phải được lưu trước khi ghi lịch sử kho."
            );
        }
    }
}