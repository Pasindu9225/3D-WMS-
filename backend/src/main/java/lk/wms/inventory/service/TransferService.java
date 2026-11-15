package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bin-to-bin transfers (v0.4 task 7): a worker scans the bin taken from and the bin put into. Retry-safe. */
@Service
public class TransferService {

    public record Moved(String fromBin, String toBin, String sku, BigDecimal qty, String batchNo, LocalDate expiryDate,
            boolean alreadyDone) {
    }

    private final StockMovementService moves;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final Lots lots;
    private final AuditService audit;

    public TransferService(StockMovementService moves, WarehouseService warehouses, ProductService products, Lots lots,
            AuditService audit) {
        this.moves = moves;
        this.warehouses = warehouses;
        this.products = products;
        this.lots = lots;
        this.audit = audit;
    }

    /** fromBin null = the receiving area (same as a putaway, but to any bin the worker chooses). */
    @Transactional
    public Moved transfer(CurrentUser cu, String warehouseId, String productId, String batchNo, LocalDate expiry,
            BigDecimal qty, String fromBin, String toBin, String clientRef) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        ProductService.Basic p = products.basic(productId);
        BinService.BinRef from = lots.bin(cu, w.id(), fromBin);
        BinService.BinRef to = lots.bin(cu, w.id(), toBin);
        if (to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TO_BIN_REQUIRED", "Scan the bin to put the goods in");
        }
        Lots.Lot lot = lots.existing(w.id(), from == null ? null : from.id(), p.id(), batchNo, expiry);
        StockMovementService.Outcome outcome = moves.move(new StockMovementService.Move(StockMovementService.Kind.TRANSFER, w.id(),
                p.id(), lot.batchNo(), lot.expiryDate(),
                from == null ? StockMovementService.Place.RECEIVING : StockMovementService.Place.bin(from.id()),
                StockMovementService.Place.bin(to.id()), qty, null, null, null, cu.userId(), clientRef));
        String fromCode = from == null ? null : from.code();
        if (outcome == StockMovementService.Outcome.MOVED) {
            audit.record(cu.tenantId(), cu.userId(), "STOCK_TRANSFERRED", "bin", to.publicId(), null,
                    Map.of("sku", p.sku(), "qty", qty, "from", fromCode == null ? "receiving" : fromCode, "to", to.code()));
        }
        return new Moved(fromCode, to.code(), p.sku(), qty, lot.batchNo(), lot.expiryDate(),
                outcome == StockMovementService.Outcome.ALREADY_DONE);
    }
}
