package lk.wms.inbound.repo;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import lk.wms.inbound.domain.PurchaseOrder;
import lk.wms.inbound.domain.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant filter by @TenantId. */
public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    Optional<Receipt> findByPublicId(String publicId);

    /** Scans and closing of one receipt run one at a time (two phones on one delivery cannot over-receive). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receipt r where r.publicId = :publicId")
    Optional<Receipt> lockByPublicId(@Param("publicId") String publicId);

    Optional<Receipt> findFirstByOrderAndStatus(PurchaseOrder order, Receipt.Status status);

    boolean existsByOrderAndStatus(PurchaseOrder order, Receipt.Status status);

    /** Receipts of one order, newest first (an order rarely has more than a few deliveries). */
    List<Receipt> findTop50ByOrderOrderByIdDesc(PurchaseOrder order);
}
