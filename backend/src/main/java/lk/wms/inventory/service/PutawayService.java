package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.BinService.Candidate;
import lk.wms.layout.service.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putaway (v0.4 task 5): received goods wait in the receiving area; the system suggests bins and the worker scans
 * the bin label to confirm. Suggestions: bins already holding the same product (same batch first), then the nearest
 * empty bins in storage zones; only bins that are not blocked and have room. The worker may scan another bin; the
 * move itself (StockMovementService) checks room again under a lock, so a suggestion can never over-fill a bin.
 */
@Service
public class PutawayService {

    public record Waiting(String productId, String sku, String name, String baseUom, String batchNo, LocalDate expiryDate,
            BigDecimal qty, Instant receivedAt) {
    }

    public enum Why { FIXED_BIN, RULE_ZONE, SAME_PRODUCT, EMPTY_STORAGE }

    public record Suggestion(String binId, String code, String zoneType, int level, Why why, double distanceM) {
    }

    public record Done(String binCode, BigDecimal qty, BigDecimal leftInReceiving, boolean alreadyDone) {
    }

    static final int MAX_SUGGESTIONS = 3;

    private final StockRepository stock;
    private final StockMovementService moves;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final ProductService products;
    private final AuditService audit;
    private final PutawayRuleService putawayRules;

    public PutawayService(StockRepository stock, StockMovementService moves, WarehouseService warehouses, BinService bins,
            ProductService products, AuditService audit, PutawayRuleService putawayRules) {
        this.stock = stock;
        this.moves = moves;
        this.warehouses = warehouses;
        this.bins = bins;
        this.products = products;
        this.audit = audit;
        this.putawayRules = putawayRules;
    }

    @Transactional(readOnly = true)
    public Page<Waiting> waiting(CurrentUser cu, String warehouseId, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        Page<Stock> page = stock.awaitingPutaway(w.id(), pageable);
        Map<Long, ProductService.Basic> names = products.basics(page.getContent().stream().map(Stock::getProductId).toList());
        return page.map(s -> {
            ProductService.Basic p = names.get(s.getProductId());
            return new Waiting(p == null ? null : p.publicId(), p == null ? "?" : p.sku(), p == null ? "" : p.name(),
                    p == null ? "" : p.baseUom(), s.getBatchNo(), s.getExpiryDate(), s.getQtyOnHand(), s.getReceivedAt());
        });
    }

    @Transactional(readOnly = true)
    public List<Suggestion> suggest(CurrentUser cu, String warehouseId, String productId, String batchNo, BigDecimal qty) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        ProductService.Basic product = products.basic(productId);
        List<Stock> inBins = stock.inBins(w.id());
        Set<Long> ids = new HashSet<>(inBins.stream().map(Stock::getProductId).toList());
        ids.add(product.id());
        Map<Long, ProductService.Measure> sizes = products.measures(ids);
        Map<Long, BigDecimal[]> load = new HashMap<>(); // bin -> [grams, cm3]
        Map<Long, Integer> sameProduct = new HashMap<>(); // bin -> 2 same batch, 1 same product
        for (Stock s : inBins) {
            ProductService.Measure m = sizes.get(s.getProductId());
            BigDecimal[] l = load.computeIfAbsent(s.getBinId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            l[0] = l[0].add(times(s.getQtyOnHand(), m == null ? null : m.weightG()));
            l[1] = l[1].add(times(s.getQtyOnHand(), m == null ? null : m.volumeCm3()));
            if (s.getProductId() == product.id()) {
                sameProduct.merge(s.getBinId(), Objects.equals(s.getBatchNo(), blankToNull(batchNo)) ? 2 : 1, Math::max);
            }
        }
        ProductService.Measure size = sizes.get(product.id());
        BigDecimal needW = times(qty, size == null ? null : size.weightG());
        BigDecimal needV = times(qty, size == null ? null : size.volumeCm3());

        List<Candidate> all = bins.putawayCandidates(cu, w.id()); // nearest first
        List<PutawayRuleService.Match> rules = putawayRules.matching(w.id(), product.id(), products.category(product.id()));
        Placed placed = placed(all, inBins, product.id());
        java.util.function.Predicate<Candidate> ok = c -> placed.refusal(product, c, qty) == null;
        Fit fit = new Fit(load, sameProduct, needW, needV, ok, home(placed.handling(product.id())));
        List<Suggestion> out = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (PutawayRuleService.Match r : rules) { // fixed bins first
            if (r.binId() != null) {
                all.stream().filter(c -> c.id() == r.binId() && !"QUARANTINE".equals(c.zoneType()) && fits(c, load.get(c.id()), needW, needV)
                        && ok.test(c))
                        .findFirst().ifPresent(c -> add(out, taken, c, Why.FIXED_BIN));
            }
        }
        boolean only = false;
        for (PutawayRuleService.Match r : rules) { // then the rules' zones
            if (r.zoneCode() != null) {
                fill(out, taken, all.stream().filter(c -> r.zoneCode().equals(c.zoneCode())).toList(), fit, Why.RULE_ZONE, Why.RULE_ZONE, false);
                only |= r.onlyZone();
            }
        }
        if (!only) { // then the built-in order: same batch, same product, nearest empty storage bin
            fill(out, taken, all, fit, Why.SAME_PRODUCT, Why.EMPTY_STORAGE, true);
        }
        return out;
    }

    /** home: the zone type empty places come from by default: STORAGE, or COLD / FROZEN for chilled and frozen goods. */
    private record Fit(Map<Long, BigDecimal[]> load, Map<Long, Integer> sameProduct, BigDecimal needW, BigDecimal needV,
            java.util.function.Predicate<Candidate> ok, String home) {
    }

    private static String home(ProductService.Handling h) {
        return switch (h.temp()) {
            case "CHILLED" -> "COLD";
            case "FROZEN" -> "FROZEN";
            default -> "STORAGE";
        };
    }

    /**
     * What already stands where (v2.1 handling rules): every product's handling, the hazard classes in each rack,
     * the pallets in each floor-row column and how much of the product each place holds.
     */
    private record Placed(Map<Long, ProductService.Handling> handlingById, Map<Long, Map<Long, String>> hazardsByRack,
            Map<String, List<long[]>> columns, Map<Long, BigDecimal> sameUnits, Map<Long, ProductService.Carton> cartons) {

        ProductService.Handling handling(long productId) {
            return handlingById.getOrDefault(productId, ProductService.Handling.NONE);
        }

        HandlingRules.Refusal refusal(ProductService.Basic product, Candidate c, BigDecimal qty) {
            List<String> rackHazards = hazardsByRack.getOrDefault(c.rackId(), Map.of()).entrySet().stream()
                    .filter(e -> e.getKey() != product.id()).map(Map.Entry::getValue).toList();
            List<HandlingRules.Below> below = columns.getOrDefault(c.rackId() + "/" + c.position(), List.of()).stream()
                    .filter(x -> x[0] < c.level()).map(x -> new HandlingRules.Below((int) x[0], handling(x[1]))).toList();
            return HandlingRules.check(product.name(), handling(product.id()), c, rackHazards, below,
                    sameUnits.getOrDefault(c.id(), BigDecimal.ZERO).add(qty), cartons.get(product.id()));
        }
    }

    private Placed placed(List<Candidate> all, List<Stock> inBins, long productId) {
        Map<Long, Candidate> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.id(), c));
        Set<Long> ids = new HashSet<>(inBins.stream().map(Stock::getProductId).toList());
        ids.add(productId);
        Map<Long, ProductService.Handling> handling = products.handling(ids);
        Map<Long, Map<Long, String>> hazards = new HashMap<>();
        Map<String, List<long[]>> columns = new HashMap<>();
        Map<Long, BigDecimal> same = new HashMap<>();
        for (Stock s : inBins) {
            Candidate c = byId.get(s.getBinId());
            if (c == null || s.getQtyOnHand().signum() <= 0) {
                continue;
            }
            ProductService.Handling h = handling.getOrDefault(s.getProductId(), ProductService.Handling.NONE);
            if (h.hazardClass() != null) {
                hazards.computeIfAbsent(c.rackId(), k -> new HashMap<>()).put(s.getProductId(), h.hazardClass());
            }
            if ("FLOOR".equals(c.style())) {
                columns.computeIfAbsent(c.rackId() + "/" + c.position(), k -> new ArrayList<>()).add(new long[] {c.level(), s.getProductId()});
            }
            if (s.getProductId() == productId) {
                same.merge(c.id(), s.getQtyOnHand(), BigDecimal::add);
            }
        }
        return new Placed(handling, hazards, columns, same, products.cartons(List.of(productId)));
    }

    /** Same batch, then same product, then empty bins (storage zones only, or any zone but quarantine) among these candidates. */
    private static void fill(List<Suggestion> out, Set<Long> taken, List<Candidate> among, Fit f, Why same, Why empty, boolean storageOnly) {
        for (int rank = 2; rank >= 1; rank--) {
            for (Candidate c : among) {
                if (f.sameProduct().getOrDefault(c.id(), 0) == rank && !"QUARANTINE".equals(c.zoneType())
                        && fits(c, f.load().get(c.id()), f.needW(), f.needV()) && f.ok().test(c)) {
                    add(out, taken, c, same);
                }
            }
        }
        for (Candidate c : among) {
            if ((storageOnly ? f.home().equals(c.zoneType()) : !"QUARANTINE".equals(c.zoneType())) && !f.load().containsKey(c.id())
                    && fits(c, null, f.needW(), f.needV()) && f.ok().test(c)) {
                add(out, taken, c, empty);
            }
        }
    }

    private static void add(List<Suggestion> out, Set<Long> taken, Candidate c, Why why) {
        if (out.size() < MAX_SUGGESTIONS && taken.add(c.id())) {
            out.add(suggestion(c, why));
        }
    }

    /** Moves goods from the receiving area into the bin whose label was scanned. Retry-safe with the key. */
    @Transactional
    public Done confirm(CurrentUser cu, String warehouseId, String productId, String batchNo, LocalDate expiry, BigDecimal qty,
            String binCode, String clientRef) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        ProductService.Basic product = products.basic(productId);
        BinService.BinRef bin = bins.byCode(cu, w.id(), binCode.trim().toUpperCase(java.util.Locale.ROOT));
        checkOnlyZone(cu, w.id(), product.id(), bin);
        if (clientRef == null || !moves.isDone(clientRef)) { // a retry already passed the rules
            checkHandling(cu, w.id(), product, bin, qty);
        }
        StockMovementService.Outcome outcome = moves.move(new StockMovementService.Move(StockMovementService.Kind.PUTAWAY, w.id(),
                product.id(), batchNo, expiry, StockMovementService.Place.RECEIVING, StockMovementService.Place.bin(bin.id()),
                qty, null, null, null, cu.userId(), clientRef));
        if (outcome == StockMovementService.Outcome.MOVED) {
            audit.record(cu.tenantId(), cu.userId(), "PUTAWAY", "bin", bin.publicId(), null,
                    Map.of("sku", product.sku(), "qty", qty, "bin", bin.code()));
        }
        BigDecimal left = stock.awaitingPutaway(w.id(), Pageable.unpaged()).stream()
                .filter(s -> s.getProductId() == product.id() && Objects.equals(s.getBatchNo(), blankToNull(batchNo))
                        && Objects.equals(s.getExpiryDate(), expiry))
                .map(Stock::getQtyOnHand).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Done(bin.code(), qty, left, outcome == StockMovementService.Outcome.ALREADY_DONE);
    }

    /** An "only this zone" rule: a bin outside its zone(s) is refused, unless a rule names that very bin. */
    private void checkOnlyZone(CurrentUser cu, long warehouseId, long productId, BinService.BinRef bin) {
        List<PutawayRuleService.Match> rules = putawayRules.matching(warehouseId, productId, products.category(productId));
        Set<String> zones = new java.util.TreeSet<>();
        rules.stream().filter(PutawayRuleService.Match::onlyZone).forEach(r -> zones.add(r.zoneCode()));
        if (zones.isEmpty() || rules.stream().anyMatch(r -> Objects.equals(r.binId(), bin.id()))) {
            return;
        }
        String zone = bins.putawayCandidates(cu, warehouseId).stream().filter(c -> c.id() == bin.id()).map(Candidate::zoneCode).findFirst()
                .orElse(null);
        if (!zones.contains(zone)) {
            throw new lk.wms.common.errors.ApiException(org.springframework.http.HttpStatus.CONFLICT, "OUTSIDE_ZONE",
                    "This product goes only in zone " + String.join(" or ", zones) + "; " + bin.code() + " is not there");
        }
    }

    /**
     * v2.1 handling rules at the scan: temperature, dangerous goods and stack limits. A bin a rule names is not
     * exempt: a fixed bin cannot make frozen fish safe in a warm zone.
     */
    // ponytail: checked before the bin lock, like checkOnlyZone; two scans in the same instant could both pass a stack
    // limit (capacity is still checked under the lock). Move into StockMovementService if that ever matters.
    private void checkHandling(CurrentUser cu, long warehouseId, ProductService.Basic product, BinService.BinRef bin, BigDecimal qty) {
        List<Candidate> all = bins.putawayCandidates(cu, warehouseId);
        Candidate c = all.stream().filter(x -> x.id() == bin.id()).findFirst().orElse(null);
        if (c == null) {
            return;
        }
        HandlingRules.Refusal r = placed(all, stock.inBins(warehouseId), product.id()).refusal(product, c, qty);
        if (r != null) {
            throw new lk.wms.common.errors.ApiException(org.springframework.http.HttpStatus.CONFLICT, r.code(), r.message());
        }
    }

    private static boolean fits(Candidate c, BigDecimal[] load, BigDecimal needW, BigDecimal needV) {
        if (c.blocked()) {
            return false;
        }
        BigDecimal w = load == null ? BigDecimal.ZERO : load[0];
        BigDecimal v = load == null ? BigDecimal.ZERO : load[1];
        boolean weightOk = c.maxWeightG() <= 0 || w.add(needW).compareTo(BigDecimal.valueOf(c.maxWeightG())) <= 0;
        boolean volumeOk = c.maxVolumeCm3() <= 0 || v.add(needV).compareTo(BigDecimal.valueOf(c.maxVolumeCm3())) <= 0;
        return weightOk && volumeOk;
    }

    private static Suggestion suggestion(Candidate c, Why why) {
        return new Suggestion(c.publicId(), c.code(), c.zoneType(), c.level(), why, Math.round(c.distanceMm() / 100.0) / 10.0);
    }

    private static BigDecimal times(BigDecimal qty, Integer perUnit) {
        return perUnit == null ? BigDecimal.ZERO : qty.multiply(BigDecimal.valueOf(perUnit));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
