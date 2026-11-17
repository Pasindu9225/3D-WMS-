package lk.wms.layout.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public record BinLabel(String publicId, String code, boolean blocked, boolean quarantine, boolean pickFace) {
    }

    /** Labels by internal id (other modules store bin ids on their rows); tenant-filtered. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, BinLabel> labels(java.util.Collection<Long> binIds) {
        java.util.Map<Long, BinLabel> out = new java.util.HashMap<>();
        if (!binIds.isEmpty()) {
            bins.labels(binIds).forEach(r -> out.put(((Number) r[0]).longValue(), new BinLabel((String) r[1], (String) r[2], (Boolean) r[3], (Boolean) r[4], (Boolean) r[5])));
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

    /**
     * Metres walked from the dispatch zone (or receiving, or the corner) through these bins in this order and back
     * (v1.2 batch picking). ponytail: straight lines between racks, not around them; enough to compare walks.
     */
    @Transactional(readOnly = true)
    public double walkMetres(long warehouseId, List<Long> binIdsInOrder) {
        java.util.Map<Long, long[]> at = new java.util.HashMap<>();
        bins.placedBins(warehouseId).forEach(b -> at.put(b.getId(), new long[] {b.getX(), b.getY()}));
        long[] start = zonePoint(warehouseId, lk.wms.layout.domain.Zone.Type.DISPATCH);
        if (start == null) {
            start = receivingPoint(warehouseId);
        }
        double mm = 0;
        long[] here = start;
        for (Long id : binIdsInOrder) {
            long[] next = at.get(id);
            if (next != null) {
                mm += Math.hypot(next[0] - here[0], next[1] - here[1]);
                here = next;
            }
        }
        mm += Math.hypot(start[0] - here[0], start[1] - here[1]);
        return Math.round(mm / 100.0) / 10.0;
    }

    /** One stop of a pick route: a rack position (mm, plan coordinates) and the bins taken from there, in order. */
    public record Stop(int step, long x, long y, List<String> binCodes) {
    }

    /** Where a pick walk starts and ends, its stops in walking order, and its length. */
    public record Route(long startX, long startY, List<Stop> stops, double metres) {
    }

    /**
     * Walking order (v1.2 task 2): from the start point, always the nearest rack not yet visited (straight line, ties by
     * bin code), and inside a rack level by level, then by code. Bins not placed on the floor go last, by code.
     * ponytail: nearest-neighbour, not an optimal tour; good for a trolley walk of tens of racks.
     */
    @Transactional(readOnly = true)
    public List<Long> walkOrder(long warehouseId, java.util.Collection<Long> binIds) {
        return walk(warehouseId, binIds).ordered();
    }

    /** The walk through these bins as stops (one per rack visit) from and back to the start point. */
    @Transactional(readOnly = true)
    public Route route(long warehouseId, java.util.Collection<Long> binIds) {
        Walk w = walk(warehouseId, binIds);
        return new Route(w.start()[0], w.start()[1], w.stops(), walkMetres(warehouseId, w.ordered()));
    }

    private record Walk(long[] start, List<Long> ordered, List<Stop> stops) {
    }

    private Walk walk(long warehouseId, java.util.Collection<Long> binIds) {
        java.util.Set<Long> wanted = new java.util.HashSet<>(binIds);
        Map<String, List<BinRepository.PlacedBin>> byRack = new java.util.LinkedHashMap<>(); // rack = its floor position
        bins.placedBins(warehouseId).stream().filter(b -> wanted.contains(b.getId()))
                .sorted(java.util.Comparator.comparing(BinRepository.PlacedBin::getCode))
                .forEach(b -> byRack.computeIfAbsent(b.getX() + ":" + b.getY(), k -> new ArrayList<>()).add(b));
        long[] start = zonePoint(warehouseId, lk.wms.layout.domain.Zone.Type.DISPATCH);
        if (start == null) {
            start = receivingPoint(warehouseId);
        }
        List<Long> ordered = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        long[] here = start;
        while (!byRack.isEmpty()) {
            final long[] from = here;
            String next = byRack.entrySet().stream()
                    .min(java.util.Comparator.<Map.Entry<String, List<BinRepository.PlacedBin>>>comparingDouble(e ->
                            Math.hypot(e.getValue().get(0).getX() - from[0], e.getValue().get(0).getY() - from[1]))
                            .thenComparing(e -> e.getValue().get(0).getCode()))
                    .orElseThrow().getKey();
            List<BinRepository.PlacedBin> rack = byRack.remove(next);
            rack.sort(java.util.Comparator.comparing((BinRepository.PlacedBin b) -> b.getLevelNo()).thenComparing(BinRepository.PlacedBin::getCode));
            rack.forEach(b -> ordered.add(b.getId()));
            here = new long[] {rack.get(0).getX(), rack.get(0).getY()};
            stops.add(new Stop(stops.size() + 1, here[0], here[1], rack.stream().map(BinRepository.PlacedBin::getCode).toList()));
        }
        binIds.stream().filter(id -> !ordered.contains(id)).sorted().forEach(ordered::add); // not on the floor: last
        return new Walk(start, ordered, stops);
    }

    private long[] receivingPoint(long warehouseId) {
        long[] p = zonePoint(warehouseId, lk.wms.layout.domain.Zone.Type.RECEIVING);
        return p == null ? new long[] {0, 0} : p;
    }

    /** The middle of the first zone of this type, or null when there is none. */
    private long[] zonePoint(long warehouseId, lk.wms.layout.domain.Zone.Type type) {
        for (lk.wms.layout.domain.Zone z : zones.findByWarehouseId(warehouseId)) {
            if (z.getType() == type) {
                var points = json.readTree(z.getPolygon());
                long sx = 0;
                long sy = 0;
                for (var p : points) {
                    sx += p.get("x").asLong();
                    sy += p.get("y").asLong();
                }
                return points.isEmpty() ? null : new long[] {sx / points.size(), sy / points.size()};
            }
        }
        return null;
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
