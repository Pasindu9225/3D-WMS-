package lk.wms.inventory.repo;

import lk.wms.inventory.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert-only use: the ledger is never updated or deleted. */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** A move already made with this Idempotency-Key (tenant-filtered). */
    boolean existsByClientRef(String clientRef);
}
