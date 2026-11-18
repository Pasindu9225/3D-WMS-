package lk.wms.layout.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.LayoutVersion;
import lk.wms.layout.domain.Rack;
import lk.wms.layout.domain.RackLevel;
import lk.wms.layout.domain.Zone;
import lk.wms.layout.repo.BinRepository;
import lk.wms.layout.repo.BinRepository.SceneBin;
import lk.wms.layout.repo.LayoutVersionRepository;
import lk.wms.layout.repo.RackLevelRepository;
import lk.wms.layout.repo.RackRepository;
import lk.wms.layout.repo.ZoneRepository;
import lk.wms.layout.service.WarehouseService.WarehouseRef;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published layout as compact arrays for the 3D viewer (instanced meshes). Not paginated on purpose: the
 * viewer needs the whole warehouse at once, and a layout is capped at 50,000 bins (about 2 MB here), see
 * SPEC "GET /warehouses/{id}/scene".
 */
@Service
public class SceneService {

    /** Bin status flags, one character per bin (bit mask as a digit): 1 = blocked, 2 = pick face. */
    static final int BLOCKED = 1;
    static final int PICK_FACE = 2;

    public record ZoneView(String code, String type, String name, int[] polygon) {
    }

    /** levels: height from the floor of each level, bottom first; bins sit on these shelves. */
    public record RackView(String code, String zone, int x, int y, int rotation, int widthMm, int depthMm, int heightMm,
            int binsPerLevel, int[] levels, String style, String uprightColor, String beamColor) {
    }

    /** Parallel arrays: bin i is in racks[rack[i]], on levels[level[i]], slot pos[i] counted from the left end. */
    public record BinArrays(String[] ids, String[] codes, int[] rack, int[] level, int[] pos) {
    }

    /** wallHeightMm: the building's wall height, so the 3D view can raise walls with real door openings. */
    public record SceneView(String publishedAt, int[] floor, int wallHeightMm, List<LayoutDocument.Opening> openings, List<ZoneView> zones,
            List<RackView> racks, BinArrays bins, String flags, int[] fill, Integer[] expiryDays, int[] picks, int[][] cartons) {
    }

    /** flags as in the scene; fill = how full each bin is, 0 to 100 (1 at least when it holds anything); expiryDays = days
     * until the earliest expiry in each bin (negative = expired), null when it holds nothing dated. */
    public record StatusView(String publishedAt, String flags, int[] fill, Integer[] expiryDays, int[] picks, int[][] cartons) {
    }

    private final WarehouseService warehouses;
    private final LayoutVersionRepository versions;
    private final ZoneRepository zones;
    private final RackRepository racks;
    private final RackLevelRepository levels;
    private final BinRepository bins;
    private final JsonMapper json;
    private final lk.wms.inventory.service.StockService stock;
    private final RackTemplateService templates;

    public SceneService(WarehouseService warehouses, LayoutVersionRepository versions, ZoneRepository zones,
            RackRepository racks, RackLevelRepository levels, BinRepository bins, JsonMapper json,
            lk.wms.inventory.service.StockService stock, RackTemplateService templates) {
        this.stock = stock;
        this.templates = templates;
        this.warehouses = warehouses;
        this.versions = versions;
        this.zones = zones;
        this.racks = racks;
        this.levels = levels;
        this.bins = bins;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public SceneView scene(CurrentUser cu, String warehouseId) {
        WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        LayoutVersion published = published(w);
        LayoutDocument doc = json.readValue(published.getLayout(), LayoutDocument.class);

        List<Zone> zoneRows = zones.findByWarehouseId(w.id());
        Map<Long, String> zoneCodes = zoneRows.stream().collect(Collectors.toMap(Zone::getId, Zone::getCode));
        List<ZoneView> zoneViews = zoneRows.stream()
                .map(z -> new ZoneView(z.getCode(), z.getType().name(), z.getName(), flat(points(z.getPolygon()))))
                .toList();

        List<Rack> rackRows = racks.findByWarehouseId(w.id());
        Map<Long, Integer> rackIndex = new HashMap<>();
        for (int i = 0; i < rackRows.size(); i++) {
            rackIndex.put(rackRows.get(i).getId(), i);
        }
        Map<Long, List<RackLevel>> levelsByRack = levels.findByRackIdIn(rackIndex.keySet()).stream()
                .sorted((a, b) -> Integer.compare(a.getLevelNo(), b.getLevelNo()))
                .collect(Collectors.groupingBy(RackLevel::getRackId));
        Map<Long, RackLevel> levelById = levelsByRack.values().stream().flatMap(List::stream)
                .collect(Collectors.toMap(RackLevel::getId, Function.identity()));

        List<SceneBin> binRows = bins.findByWarehouseIdOrderById(w.id());
        int n = binRows.size();
        BinArrays arrays = new BinArrays(new String[n], new String[n], new int[n], new int[n], new int[n]);
        int[] binsPerLevel = new int[rackRows.size()];
        for (int i = 0; i < n; i++) {
            SceneBin b = binRows.get(i);
            RackLevel level = levelById.get(b.getLevelId());
            int r = rackIndex.get(level.getRackId());
            arrays.ids()[i] = b.getPublicId();
            arrays.codes()[i] = b.getCode();
            arrays.rack()[i] = r;
            arrays.level()[i] = level.getLevelNo() - 1;
            arrays.pos()[i] = b.getPositionNo() - 1;
            binsPerLevel[r] = Math.max(binsPerLevel[r], b.getPositionNo());
        }
        List<RackView> rackViews = new ArrayList<>(rackRows.size());
        Map<Long, lk.wms.layout.domain.RackTemplate> looks = templates.looks(rackRows.stream().map(Rack::getTemplateId).collect(Collectors.toSet()));
        for (int i = 0; i < rackRows.size(); i++) {
            Rack r = rackRows.get(i);
            int[] heights = levelsByRack.getOrDefault(r.getId(), List.of()).stream()
                    .mapToInt(RackLevel::getHeightFromFloorMm).toArray();
            rackViews.add(new RackView(r.getCode(), zoneCodes.get(r.getZoneId()), r.getPosXMm(), r.getPosYMm(),
                    r.getRotationDeg(), r.getWidthMm(), r.getDepthMm(), r.getHeightMm(), binsPerLevel[i], heights,
                    look(looks, r).getStyle().name(), look(looks, r).getUprightColor(), look(looks, r).getBeamColor()));
        }
        return new SceneView(published.getPublishedAt().toString(), flat(doc.floor()), warehouses.wallHeightMm(w.publicId()), doc.openings(), zoneViews,
                rackViews, arrays, flags(binRows), fill(w.id(), binRows), expiryDays(w.id(), binRows),
                picks(w.id(), binRows), cartons(w.id(), binRows));
    }

    /** Status only, in the same bin order as the scene; the viewer refetches the scene when publishedAt changes. */
    @Transactional(readOnly = true)
    public StatusView status(CurrentUser cu, String warehouseId) {
        WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        List<SceneBin> rows = bins.findByWarehouseIdOrderById(w.id());
        return new StatusView(published(w).getPublishedAt().toString(), flags(rows), fill(w.id(), rows), expiryDays(w.id(), rows),
                picks(w.id(), rows), cartons(w.id(), rows));
    }

    private LayoutVersion published(WarehouseRef w) {
        return versions.findFirstByWarehouseIdAndStatusOrderByPublishedAtDesc(w.id(), LayoutVersion.Status.PUBLISHED)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_PUBLISHED",
                        "This warehouse has no published layout yet"));
    }

    /** Days until the earliest expiry in each bin (Sri Lanka day, like the dashboard), null for bins with nothing dated. */
    private Integer[] expiryDays(long warehouseId, List<SceneBin> rows) {
        Map<Long, java.time.LocalDate> earliest = stock.binEarliestExpiry(warehouseId);
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
        Integer[] out = new Integer[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            java.time.LocalDate d = earliest.get(rows.get(i).getId());
            out[i] = d == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(today, d);
        }
        return out;
    }

/** Each bin's real cartons [length, width, height mm, count] (v2.1), null when its main product has no carton size. */
    private int[][] cartons(long warehouseId, List<SceneBin> rows) {
        Map<Long, int[]> byBin = stock.binCartons(warehouseId);
        int[][] out = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = byBin.get(rows.get(i).getId());
        }
        return out;
    }

    /** A rack's 3D look from its rack type; the classic look when the type was archived. */
    private static lk.wms.layout.domain.RackTemplate look(Map<Long, lk.wms.layout.domain.RackTemplate> looks, Rack r) {
        lk.wms.layout.domain.RackTemplate t = looks.get(r.getTemplateId());
        return t != null ? t : CLASSIC;
    }

    private static final lk.wms.layout.domain.RackTemplate CLASSIC = new lk.wms.layout.domain.RackTemplate("-", "-", 1000, 1000, 1000, 1, 1, 1);

    /** Lines picked from each bin in the last 30 days (v1.2 heat map), 0 when never. */
    // ponytail: re-counted on every /bin-status call; cache per warehouse for a minute if picking gets heavy
    private int[] picks(long warehouseId, List<SceneBin> rows) {
        Map<Long, Integer> picks = stock.binPicks(warehouseId);
        int[] out = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = picks.getOrDefault(rows.get(i).getId(), 0);
        }
        return out;
    }

    /** Fullness by weight or volume, whichever is higher; a bin holding goods of unknown size shows as 1%. */
    private int[] fill(long warehouseId, List<SceneBin> rows) {
        Map<Long, lk.wms.inventory.service.StockService.Load> loads = stock.binLoads(warehouseId);
        int[] out = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            SceneBin b = rows.get(i);
            var load = loads.get(b.getId());
            if (load == null) {
                continue;
            }
            double byWeight = b.getMaxWeightG() > 0 ? load.weightG().doubleValue() / b.getMaxWeightG() : 0;
            double byVolume = b.getMaxVolumeCm3() > 0 ? load.volumeCm3().doubleValue() / b.getMaxVolumeCm3() : 0;
            out[i] = (int) Math.min(100, Math.max(1, Math.ceil(Math.max(byWeight, byVolume) * 100)));
        }
        return out;
    }

    private static String flags(List<SceneBin> rows) {
        StringBuilder sb = new StringBuilder(rows.size());
        for (SceneBin b : rows) {
            sb.append((char) ('0' + (Boolean.TRUE.equals(b.getBlocked()) ? BLOCKED : 0)
                    + (Boolean.TRUE.equals(b.getPickFace()) ? PICK_FACE : 0)));
        }
        return sb.toString();
    }

    private List<LayoutDocument.Point> points(String polygonJson) {
        return json.readValue(polygonJson, new TypeReference<List<LayoutDocument.Point>>() { });
    }

    private static int[] flat(List<LayoutDocument.Point> points) {
        int[] out = new int[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            out[2 * i] = points.get(i).x();
            out[2 * i + 1] = points.get(i).y();
        }
        return out;
    }
}
