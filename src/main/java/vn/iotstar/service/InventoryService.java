package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.InventoryTransactionRepository;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final InventoryTransactionRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Product product, int before, int after, InventoryTransactionType type,
                       User actor, CustomerOrder order, String note) {
        if (before == after) return;
        if (before < 0 || after < 0 || actor == null || type == null) {
            throw new IllegalArgumentException("Biến động kho không hợp lệ.");
        }
        InventoryTransaction transaction = new InventoryTransaction();
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
        transaction.setNote(note == null ? null : note.trim());
        repository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<InventoryTransaction> forShop(Shop shop, Long productId, int page) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), 20);
        return productId == null
            ? repository.findByShopIdOrderByCreatedAtDescIdDesc(shop.getId(), pageable)
            : repository.findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(shop.getId(), productId, pageable);
    }
}
