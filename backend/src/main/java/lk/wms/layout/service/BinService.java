package lk.wms.layout.service;

import java.util.List;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.Bin;
import lk.wms.layout.repo.BinRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published bins: list and single-bin edits. Every call checks warehouse access first (SEC-10). */
@Service
public class BinService {

    public record BinView(String id, String code, long maxWeightG, long maxVolumeCm3, boolean customCapacity,
            boolean pickFace, boolean blocked) {
    }

    private final BinRepository bins;
    private final WarehouseService warehouses;
    private final AuditService audit;

    public BinService(BinRepository bins, WarehouseService warehouses, AuditService audit) {
        this.bins = bins;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<BinView> list(CurrentUser cu, String warehouseId, String codePrefix, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        return bins.findByWarehouseIdAndCodeStartingWith(w.id(), codePrefix == null ? "" : codePrefix.trim(), pageable)
                .map(BinService::view);
    }

    /** Capacity set here is kept by later publishes; blocked bins get no putaway (v0.4). */
    @Transactional
    public BinView edit(CurrentUser cu, String binId, Long maxWeightG, Long maxVolumeCm3, Boolean pickFace,
            Boolean blocked) {
        Bin bin = bins.findByPublicId(binId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BIN_NOT_FOUND", "Bin not found"));
        try {
            warehouses.requireAccess(cu, bin.getWarehouseId());
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "BIN_NOT_FOUND", "Bin not found"); // same answer as missing
        }
        BinView before = view(bin);
        bin.edit(maxWeightG, maxVolumeCm3, pickFace, blocked);
        audit.record(cu.tenantId(), cu.userId(), "BIN_UPDATED", "bin", bin.getPublicId(), before, view(bin));
        return view(bin);
    }

    public record LabelBatch(String warehouseCode, List<String> codes) {
    }

    static final int MAX_LABELS = 10_000;

    /** Bin codes to print (whole warehouse, a zone or a rack by code prefix). */
    @Transactional(readOnly = true)
    public LabelBatch labels(CurrentUser cu, String warehouseId, String codePrefix) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        List<String> codes = bins.findByWarehouseIdAndCodeStartingWithOrderByCode(w.id(),
                codePrefix == null ? "" : codePrefix.trim()).stream().map(BinRepository.CodeOnly::getCode).toList();
        if (codes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_BINS", "No published bins match. Publish the layout first.");
        }
        if (codes.size() > MAX_LABELS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY_LABELS", "Print at most 10,000 labels at once");
        }
        return new LabelBatch(w.code(), codes);
    }

    private static BinView view(Bin b) {
        return new BinView(b.getPublicId(), b.getCode(), b.getMaxWeightG(), b.getMaxVolumeCm3(), b.isCustomCapacity(),
                b.isPickFace(), b.isBlocked());
    }
}
