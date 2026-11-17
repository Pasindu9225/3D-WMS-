package lk.wms.layout.service;

import java.util.List;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.Bin;
import lk.wms.layout.repo.BinRepository;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher events;

    private final lk.wms.layout.repo.ZoneRepository zones;
    private final tools.jackson.databind.json.JsonMapper json;

    public BinService(BinRepository bins, WarehouseService warehouses, AuditService audit,
            ApplicationEventPublisher events, lk.wms.layout.repo.ZoneRepository zones, tools.jackson.databind.json.JsonMapper json) {
        this.zones = zones;
        this.json = json;
        this.bins = bins;
        this.warehouses = warehouses;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<BinView> list(CurrentUser cu, String warehouseId, String codePrefix, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        return bins.findByWarehouseIdAndCodeStartingWith(w.id(), codePrefix == null ? "" : codePrefix.trim(), pageable)
                .map(BinService::view);
    }

    /** What stock moves need to know about a bin. Ids are internal: only for other modules' services. */
    public record BinRef(long id, String publicId, long warehouseId, String code, long maxWeightG, long maxVolumeCm3,
            boolean blocked) {
    }

    /**
     * For StockMovementService: the bin, write-locked until the caller's transaction ends, so two moves into it
     * are checked against its capacity one after the other. Must run inside that transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public BinRef lockForStock(long binId) {
        Bin b = bins.lockForStock(binId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BIN_NOT_FOUND", "Bin not found"));
        return ref(b);
    }

    /** A bin by its public id (scanned label or picked in the UI), if this user may work in its warehouse. */
    @Transactional(readOnly = true)
    public BinRef byPublicId(CurrentUser cu, String publicId) {
        Bin b = bins.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BIN_NOT_FOUND", "Bin not found"));
        warehouses.requireAccess(cu, b.getWarehouseId());
        return ref(b);
    }

    /** A bin by its printed code in one warehouse (what a worker scans from the label). */
    @Transactional(readOnly = true)
    public BinRef byCode(CurrentUser cu, long warehouseId, String code) {
        warehouses.requireAccess(cu, warehouseId);
        Bin b = bins.findByWarehouseIdAndCode(warehouseId, code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BIN_NOT_FOUND", "No bin " + code + " in this warehouse"));
        return ref(b);
    }

    /** A bin as stock screens show it. */
    /** quarantine: the bin is in a QUARANTINE zone (v1.1): its stock is never picked. */
    public record BinLabel(String publicId, String code, boolean blocked, boolean quarantine) {
    }

    /** Labels by internal id (other modules store bin ids on their rows); tenant-filtered. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, BinLabel> labels(java.util.Collection<Long> binIds) {
        java.util.Map<Long, BinLabel> out = new java.util.HashMap<>();
        if (!binIds.isEmpty()) {
            bins.labels(binIds).forEach(r -> out.put(((Number) r[0]).longValue(), new BinLabel((String) r[1], (String) r[2], (Boolean) r[3], (Boolean) r[4])));
        }
        return out;
    }

    /** Internal ids of the bins whose code starts with this text (up to 1,000), after the access check. */
    @Transactional(readOnly = true)
    public List<Long> idsByCodePrefix(CurrentUser cu, long warehouseId, String prefix) {
        warehouses.requireAccess(cu, warehouseId);
        return bins.findByWarehouseIdAndCodeStartingWith(warehouseId, prefix.trim().toUpperCase(java.util.Locale.ROOT),
                org.springframework.data.domain.PageRequest.of(0, 1000)).map(Bin::getId).getContent();
    }

    /** A bin a putaway could use: zone type, level, and its distance from where goods arrive. */
    public record Candidate(long id, String publicId, String code, String zoneType, int level, long distanceMm,
            boolean blocked, long maxWeightG, long maxVolumeCm3) {
    }

    /**
     * Every bin of the warehouse for putaway, nearest first. Distance is from the middle of the receiving zone
     * (where received goods wait) to the bin's rack, in a straight line; with no receiving zone, from the floor's
     * top-left corner. ponytail: straight-line, not walking distance around racks; good enough to rank bins.
     */
    @Transactional(readOnly = true)
    public List<Candidate> putawayCandidates(CurrentUser cu, long warehouseId) {
        warehouses.requireAccess(cu, warehouseId);
        long[] origin = receivingPoint(warehouseId);
        return bins.placedBins(warehouseId).stream()
                .map(b -> new Candidate(b.getId(), b.getPublicId(), b.getCode(), b.getZoneType().name(), b.getLevelNo(),
                        Math.round(Math.hypot(b.getX() - origin[0], b.getY() - origin[1])), b.getBlocked(), b.getMaxWeightG(),
                        b.getMaxVolumeCm3()))
                .sorted(java.util.Comparator.comparingLong(Candidate::distanceMm).thenComparingInt(Candidate::level)
                        .thenComparing(Candidate::code))
                .toList();
    }

    private long[] receivingPoint(long warehouseId) {
        for (lk.wms.layout.domain.Zone z : zones.findByWarehouseId(warehouseId)) {
            if (z.getType() == lk.wms.layout.domain.Zone.Type.RECEIVING) {
                var points = json.readTree(z.getPolygon());
                long sx = 0;
                long sy = 0;
                for (var p : points) {
                    sx += p.get("x").asLong();
                    sy += p.get("y").asLong();
                }
                return points.isEmpty() ? new long[] {0, 0} : new long[] {sx / points.size(), sy / points.size()};
            }
        }
        return new long[] {0, 0};
    }

    private static BinRef ref(Bin b) {
        return new BinRef(b.getId(), b.getPublicId(), b.getWarehouseId(), b.getCode(), b.getMaxWeightG(),
                b.getMaxVolumeCm3(), b.isBlocked());
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
        events.publishEvent(new LiveBins.BinChanged(warehouses.publicIdOf(bin.getWarehouseId()), bin.getPublicId(),
                (bin.isBlocked() ? SceneService.BLOCKED : 0) + (bin.isPickFace() ? SceneService.PICK_FACE : 0)));
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
