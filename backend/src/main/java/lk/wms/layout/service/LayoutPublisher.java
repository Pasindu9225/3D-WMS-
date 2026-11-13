package lk.wms.layout.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.Bin;
import lk.wms.layout.domain.LayoutVersion;
import lk.wms.layout.domain.Rack;
import lk.wms.layout.domain.RackLevel;
import lk.wms.layout.domain.RackTemplate;
import lk.wms.layout.domain.Warehouse;
import lk.wms.layout.domain.Zone;
import lk.wms.layout.repo.BinRepository;
import lk.wms.layout.repo.LayoutVersionRepository;
import lk.wms.layout.repo.RackLevelRepository;
import lk.wms.layout.repo.RackRepository;
import lk.wms.layout.repo.WarehouseRepository;
import lk.wms.layout.repo.ZoneRepository;
import lk.wms.layout.service.LayoutValidator.Issue;
import lk.wms.layout.service.LayoutValidator.Severity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publish: turns the draft into the zone / rack / level / bin rows that stock, labels and the 3D view
 * use. Rows are matched by zone code, rack code, level number and bin position, so an existing bin
 * keeps its id across publishes; only its code follows its rack and zone.
 */
@Service
public class LayoutPublisher {

    public record PublishResult(int zones, int racks, int levels, int bins, int binsAdded, int binsRemoved,
            int binsRenamed) {
    }

    private final WarehouseRepository warehouses;
    private final WarehouseService warehouseAccess;
    private final LayoutVersionRepository versions;
    private final RackTemplateService templates;
    private final ZoneRepository zones;
    private final RackRepository racks;
    private final RackLevelRepository levels;
    private final BinRepository bins;
    private final AuditService audit;
    private final JsonMapper json;

    public LayoutPublisher(WarehouseRepository warehouses, WarehouseService warehouseAccess,
            LayoutVersionRepository versions, RackTemplateService templates, ZoneRepository zones, RackRepository racks,
            RackLevelRepository levels, BinRepository bins, AuditService audit, JsonMapper json) {
        this.warehouses = warehouses;
        this.warehouseAccess = warehouseAccess;
        this.versions = versions;
        this.templates = templates;
        this.zones = zones;
        this.racks = racks;
        this.levels = levels;
        this.bins = bins;
        this.audit = audit;
        this.json = json;
    }

    // ponytail: one INSERT per new bin (IDENTITY keys cannot be batched): a 5,000-bin first publish takes
    // a few seconds. Switch to sequence-style keys if publishes ever need to be faster.
    @Transactional
    public PublishResult publish(CurrentUser cu, String warehouseId, int baseRevision) {
        warehouseAccess.requireAccess(cu, warehouseId);
        Warehouse w = warehouses.lockByPublicId(warehouseId).orElseThrow(); // no save or publish in between
        LayoutVersion draft = versions.findFirstByWarehouseIdAndStatus(w.getId(), LayoutVersion.Status.DRAFT)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_DRAFT", "There are no changes to publish"));
        if (draft.getRevision() != baseRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "LAYOUT_CHANGED",
                    "Someone else saved this layout. Reload to see their changes.");
        }
        LayoutDocument doc = json.readValue(draft.getLayout(), LayoutDocument.class);
        Map<String, RackTemplate> types = templates.entities(
                doc.racks().stream().map(LayoutDocument.Rack::templateId).collect(Collectors.toSet()));
        // Checked again: a rack type may have been archived since the draft was saved.
        List<Issue> errors = LayoutValidator.validate(doc, types.values().stream().collect(Collectors.toMap(
                RackTemplate::getPublicId, t -> new LayoutValidator.Footprint(t.getWidthMm(), t.getDepthMm()))))
                .stream().filter(i -> i.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAYOUT_INVALID",
                    errors.size() + " problem(s) must be fixed before publishing", errors);
        }

        Map<String, Zone> zoneByCode = zones.findByWarehouseId(w.getId()).stream()
                .collect(Collectors.toMap(Zone::getCode, Function.identity()));
        Map<String, Rack> rackByCode = racks.findByWarehouseId(w.getId()).stream()
                .collect(Collectors.toMap(Rack::getCode, Function.identity()));
        List<Long> rackIds = rackByCode.values().stream().map(Rack::getId).toList();
        Map<Long, List<RackLevel>> levelsByRack = rackIds.isEmpty() ? Map.of()
                : levels.findByRackIdIn(rackIds).stream().collect(Collectors.groupingBy(RackLevel::getRackId));
        Map<Long, List<Bin>> binsByLevel = bins.findByWarehouseId(w.getId()).stream()
                .collect(Collectors.groupingBy(Bin::getLevelId));

        // 1. Racks no longer in the layout go, with their levels and bins.
        // ponytail: from v0.4 this refuses racks whose bins hold stock.
        Set<String> keepRacks = doc.racks().stream().map(LayoutDocument.Rack::code).collect(Collectors.toSet());
        List<Long> deadBins = new ArrayList<>();
        List<Long> deadLevels = new ArrayList<>();
        List<Long> deadRacks = new ArrayList<>();
        for (Rack r : rackByCode.values()) {
            if (!keepRacks.contains(r.getCode())) {
                deadRacks.add(r.getId());
                for (RackLevel l : levelsByRack.getOrDefault(r.getId(), List.of())) {
                    deadLevels.add(l.getId());
                    binsByLevel.getOrDefault(l.getId(), List.of()).forEach(b -> deadBins.add(b.getId()));
                }
            }
        }

        // 2. Zones: update or create (deleting the old ones waits until no rack points at them).
        for (LayoutDocument.Zone z : doc.zones()) {
            Zone zone = zoneByCode.computeIfAbsent(z.code(), code -> new Zone(w.getId(), code));
            zone.set(z.type(), z.name(), json.writeValueAsString(z.polygon()));
            zones.save(zone);
        }

        // 3. Racks, levels and bins: work out the target for every slot.
        record Slot(Bin bin, String code, long weightG, long volumeCm3) {
        }
        List<Slot> slots = new ArrayList<>();
        for (LayoutDocument.Rack dr : doc.racks()) {
            RackTemplate t = types.get(dr.templateId());
            Rack rack = rackByCode.computeIfAbsent(dr.code(), code -> new Rack(w.getId(), code));
            rack.place(zoneByCode.get(dr.zone()).getId(), t, dr.x(), dr.y(), dr.rotation());
            racks.save(rack);

            Map<Integer, RackLevel> existingLevels = levelsByRack.getOrDefault(rack.getId(), List.of()).stream()
                    .collect(Collectors.toMap(RackLevel::getLevelNo, Function.identity()));
            int levelHeight = t.getHeightMm() / t.getLevels();
            long binWeight = t.getMaxWeightPerLevelG() / t.getBinsPerLevel();
            long binVolumeCm3 = (long) t.getWidthMm() / t.getBinsPerLevel() * t.getDepthMm() * levelHeight / 1000;
            for (int n = 1; n <= t.getLevels(); n++) {
                RackLevel level = existingLevels.remove(n);
                if (level == null) {
                    level = new RackLevel(rack.getId(), n);
                }
                level.set((n - 1) * levelHeight, t.getMaxWeightPerLevelG());
                levels.save(level);
                Map<Integer, Bin> existingBins = binsByLevel.getOrDefault(level.getId(), List.of()).stream()
                        .collect(Collectors.toMap(Bin::getPositionNo, Function.identity()));
                for (int p = 1; p <= t.getBinsPerLevel(); p++) {
                    Bin bin = existingBins.remove(p);
                    if (bin == null) {
                        bin = new Bin(w.getId(), level.getId(), p);
                    }
                    slots.add(new Slot(bin, binCode(w.getBinCodePattern(), dr.zone(), dr.code(), n, p), binWeight,
                            binVolumeCm3));
                }
                existingBins.values().forEach(b -> deadBins.add(b.getId())); // fewer bins per level now
            }
            for (RackLevel extra : existingLevels.values()) { // fewer levels now
                deadLevels.add(extra.getId());
                binsByLevel.getOrDefault(extra.getId(), List.of()).forEach(b -> deadBins.add(b.getId()));
            }
        }
        if (!deadBins.isEmpty()) {
            bins.deleteByIds(deadBins);
        }
        if (!deadLevels.isEmpty()) {
            levels.deleteByIds(deadLevels);
        }
        if (!deadRacks.isEmpty()) {
            racks.deleteByIds(deadRacks);
        }

        // 4. Bin codes in two steps: park the ones that change, then give every bin its final code.
        int renamed = 0;
        for (Slot s : slots) {
            if (s.bin().getId() != null && !s.code().equals(s.bin().getCode())) {
                s.bin().parkCode();
                renamed++;
            }
        }
        bins.flush();
        int added = 0;
        for (Slot s : slots) {
            if (s.bin().getId() == null) {
                added++;
            }
            s.bin().layOut(s.code(), s.weightG(), s.volumeCm3());
            bins.save(s.bin());
        }
        bins.flush();

        // 5. Zones nobody uses any more.
        Set<String> keepZones = new HashSet<>();
        doc.zones().forEach(z -> keepZones.add(z.code()));
        List<Long> deadZones = zoneByCode.values().stream().filter(z -> !keepZones.contains(z.getCode()))
                .map(Zone::getId).toList();
        if (!deadZones.isEmpty()) {
            zones.deleteByIds(deadZones);
        }

        w.publish(json.writeValueAsString(doc.floor().stream().map(pt -> new int[] {pt.x(), pt.y()}).toList()));
        draft.markPublished(cu.userId());
        int levelCount = doc.racks().stream().mapToInt(r -> types.get(r.templateId()).getLevels()).sum();
        PublishResult result = new PublishResult(doc.zones().size(), doc.racks().size(), levelCount, slots.size(), added,
                deadBins.size(), renamed);
        audit.record(cu.tenantId(), cu.userId(), "LAYOUT_PUBLISHED", "warehouse", w.getPublicId(), null, result);
        return result;
    }

    /** "{zone}-{rack}-{level}-{bin}" with two-digit level and bin numbers, e.g. A-03-02-05. */
    static String binCode(String pattern, String zone, String rack, int level, int position) {
        return pattern.replace("{zone}", zone).replace("{rack}", rack)
                .replace("{level}", String.format("%02d", level)).replace("{bin}", String.format("%02d", position));
    }
}
