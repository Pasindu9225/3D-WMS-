package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.auth.service.UserService;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.domain.StockMovement;
import lk.wms.inventory.repo.StockMovementRepository;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock screens (v0.4 task 8): what is where, totals per product, the movement history; and 3D fill levels. */
@Service
public class StockService {

    public record StockRow(String productId, String sku, String name, String baseUom, String binId, String binCode,
            String batchNo, LocalDate expiryDate, BigDecimal qtyOnHand, BigDecimal qtyReserved, Instant receivedAt) {
    }

    public record ProductTotal(String productId, String sku, String name, String baseUom, BigDecimal onHand,
            BigDecimal inReceiving, long bins, BigDecimal reserved) {
    }

    public record MovementRow(long id, String type, String sku, String name, String baseUom, String batchNo,
            LocalDate expiryDate, String from, String to, BigDecimal qty, String reason, String refType, String user,
            Instant at) {
    }

    /** Weight and volume in one bin, from product sizes; unmeasured = it holds something whose size is unknown. */
    public record Load(BigDecimal weightG, BigDecimal volumeCm3, boolean unmeasured) {
    }

    /** Shown for the receiving area wherever a bin code would be. */
    public static final String RECEIVING = "RECEIVING";

    private final StockRepository stock;
    private final StockMovementRepository ledger;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final UserService users;

    public StockService(StockRepository stock, StockMovementRepository ledger, ProductService products,
            WarehouseService warehouses, BinService bins, UserService users) {
        this.stock = stock;
        this.ledger = ledger;
        this.products = products;
        this.warehouses = warehouses;
        this.bins = bins;
        this.users = users;
    }

    /** Which of these bins hold stock (internal ids); a layout publish may not remove them. */
    @Transactional(readOnly = true)
    public List<Long> binsHoldingStock(Collection<Long> binIds) {
        return binIds.isEmpty() ? List.of() : stock.binsWithStock(binIds);
    }

    /**
     * Stock rows: optionally one product, one bin (public id), bins whose code starts with a text, or only what is
     * waiting in the receiving area.
     */
    @Transactional(readOnly = true)
    public Page<StockRow> rows(CurrentUser cu, String warehouseId, String productId, String binId, String binPrefix,
            boolean receivingOnly, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        Long product = productId == null ? null : products.basic(productId).id();
        List<Long> binIds = null;
        if (binId != null) {
            BinService.BinRef b = bins.byPublicId(cu, binId);
            binIds = b.warehouseId() == w.id() ? List.of(b.id()) : List.of(-1L);
        } else if (binPrefix != null && !binPrefix.isBlank()) {
            binIds = bins.idsByCodePrefix(cu, w.id(), binPrefix);
        }
        Page<Stock> page = stock.search(w.id(), product, binIds == null,
                binIds == null || binIds.isEmpty() ? List.of(-1L) : binIds, receivingOnly, pageable);
        Map<Long, ProductService.Basic> names = products.basics(page.getContent().stream().map(Stock::getProductId).toList());
        Map<Long, BinService.BinLabel> labels = bins.labels(page.getContent().stream().map(Stock::getBinId)
                .filter(java.util.Objects::nonNull).toList());
        return page.map(s -> {
            ProductService.Basic p = names.get(s.getProductId());
            BinService.BinLabel b = s.getBinId() == null ? null : labels.get(s.getBinId());
            return new StockRow(p == null ? null : p.publicId(), p == null ? "?" : p.sku(), p == null ? "" : p.name(),
                    p == null ? "" : p.baseUom(), b == null ? null : b.publicId(), b == null ? RECEIVING : b.code(),
                    s.getBatchNo(), s.getExpiryDate(), s.getQtyOnHand(), s.getQtyReserved(), s.getReceivedAt());
        });
    }

    /** Totals per product in a warehouse, optionally only products matching a search. */
    @Transactional(readOnly = true)
    public Page<ProductTotal> byProduct(CurrentUser cu, String warehouseId, String query, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        boolean any = query == null || query.isBlank();
        List<Long> ids = any ? List.of(-1L) : products.idsMatching(query);
        Page<Object[]> page = stock.totalsByProduct(w.id(), any, ids.isEmpty() ? List.of(-1L) : ids, pageable);
        Map<Long, ProductService.Basic> names = products.basics(page.getContent().stream()
                .map(r -> ((Number) r[0]).longValue()).toList());
        return page.map(r -> {
            ProductService.Basic p = names.get(((Number) r[0]).longValue());
            return new ProductTotal(p == null ? null : p.publicId(), p == null ? "?" : p.sku(), p == null ? "" : p.name(),
                    p == null ? "" : p.baseUom(), (BigDecimal) r[1], (BigDecimal) r[2], ((Number) r[3]).longValue(), (BigDecimal) r[4]);
        });
    }

    /** The ledger, newest first. Dates are whole days (UTC); to is inclusive. */
    @Transactional(readOnly = true)
    public Page<MovementRow> movements(CurrentUser cu, String warehouseId, String productId, String binId,
            StockMovement.Type type, LocalDate from, LocalDate to, Pageable pageable) {
        WarehouseService.WarehouseRef w = warehouses.requireAccess(cu, warehouseId);
        Long product = productId == null ? null : products.basic(productId).id();
        Long bin = binId == null ? null : bins.byPublicId(cu, binId).id();
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to == null ? Instant.now().plusSeconds(60) : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Page<StockMovement> page = ledger.search(w.id(), product, bin, type, start, end, pageable);
        Set<Long> productIds = new HashSet<>();
        Set<Long> binIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (StockMovement m : page.getContent()) {
            productIds.add(m.getProductId());
            userIds.add(m.getUserId());
            if (m.getFromBinId() != null) {
                binIds.add(m.getFromBinId());
            }
            if (m.getToBinId() != null) {
                binIds.add(m.getToBinId());
            }
        }
        Map<Long, ProductService.Basic> names = products.basics(productIds);
        Map<Long, BinService.BinLabel> labels = bins.labels(binIds);
        Map<Long, UserService.UserView> people = users.viewsByInternalId(userIds);
        return page.map(m -> {
            ProductService.Basic p = names.get(m.getProductId());
            UserService.UserView u = people.get(m.getUserId());
            return new MovementRow(m.getId(), m.getType().name(), p == null ? "?" : p.sku(), p == null ? "" : p.name(),
                    p == null ? "" : p.baseUom(), m.getBatchNo(), m.getExpiryDate(),
                    side(m.getFromBinId(), m.isFromReceiving(), labels), side(m.getToBinId(), m.isToReceiving(), labels),
                    m.getQty(), m.getReason(), m.getRefType(), u == null ? null : u.fullName(), m.getCreatedAt());
        });
    }

    /** Load per bin of a warehouse (for 3D fill levels). The caller has checked access to the warehouse. */
    @Transactional(readOnly = true)
    public Map<Long, Load> binLoads(long warehouseId) {
        List<Object[]> rows = stock.binContents(warehouseId);
        Map<Long, ProductService.Measure> sizes = products.measures(rows.stream().map(r -> ((Number) r[1]).longValue()).distinct().toList());
        Map<Long, Load> out = new HashMap<>();
        for (Object[] r : rows) {
            ProductService.Measure m = sizes.get(((Number) r[1]).longValue());
            BigDecimal qty = (BigDecimal) r[2];
            boolean unknown = m == null || (m.weightG() == null && m.volumeCm3() == null);
            BigDecimal w = m == null || m.weightG() == null ? BigDecimal.ZERO : qty.multiply(BigDecimal.valueOf(m.weightG()));
            BigDecimal v = m == null || m.volumeCm3() == null ? BigDecimal.ZERO : qty.multiply(BigDecimal.valueOf(m.volumeCm3()));
            out.merge(((Number) r[0]).longValue(), new Load(w, v, unknown),
                    (a, b) -> new Load(a.weightG().add(b.weightG()), a.volumeCm3().add(b.volumeCm3()), a.unmeasured() || b.unmeasured()));
        }
        return out;
    }

    private static String side(Long binId, boolean receiving, Map<Long, BinService.BinLabel> labels) {
        if (binId != null) {
            BinService.BinLabel b = labels.get(binId);
            return b == null ? "?" : b.code();
        }
        return receiving ? RECEIVING : null;
    }
}
