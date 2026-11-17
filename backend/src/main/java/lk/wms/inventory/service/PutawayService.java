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
        Fit fit = new Fit(load, sameProduct, needW, needV);
        List<Suggestion> out = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (PutawayRuleService.Match r : rules) { // fixed bins first
            if (r.binId() != null) {
                all.stream().filter(c -> c.id() == r.binId() && !"QUARANTINE".equals(c.zoneType()) && fits(c, load.get(c.id()), needW, needV))
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

    private record Fit(Map<Long, BigDecimal[]> load, Map<Long, Integer> sameProduct, BigDecimal needW, BigDecimal needV) {
    }

    /** Same batch, then same product, then empty bins (storage zones only, or any zone but quarantine) among these candidates. */
    private static void fill(List<Suggestion> out, Set<Long> taken, List<Candidate> among, Fit f, Why same, Why empty, boolean storageOnly) {
        for (int rank = 2; rank >= 1; rank--) {
            for (Candidate c : among) {
                if (f.sameProduct().getOrDefault(c.id(), 0) == rank && !"QUARANTINE".equals(c.zoneType())
                        && fits(c, f.load().get(c.id()), f.needW(), f.needV())) {
                    add(out, taken, c, same);
                }
            }
        }
        for (Candidate c : among) {
            if ((storageOnly ? "STORAGE".equals(c.zoneType()) : !"QUARANTINE".equals(c.zoneType())) && !f.load().containsKey(c.id())
                    && fits(c, null, f.needW(), f.needV())) {
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
