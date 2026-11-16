package lk.wms.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.domain.Reservation;
import lk.wms.inventory.domain.Stock;
import lk.wms.inventory.domain.StockMovement;
import lk.wms.inventory.repo.ReservationRepository;
import lk.wms.inventory.repo.StockMovementRepository;
import lk.wms.inventory.repo.StockRepository;
import lk.wms.layout.service.BinService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY code that changes stock (SEC-26). Each move runs in one transaction: it locks the target bin (capacity),
 * then the stock rows on both sides in a fixed order (no deadlocks between opposite moves), checks there is enough
 * to take, changes the rows and appends one ledger line. So stock always equals the sum of its movements, a bin is
 * never filled past its weight or volume, and two workers can never take the same units twice.
 */
@Service
public class StockMovementService {

    /** Why stock moves; the public face of the ledger's movement type. */
    public enum Kind { RECEIVE, PUTAWAY, TRANSFER, ADJUST, PICK, SHIP, RETURN, COUNT }

    /** A side of a move: a bin, or the receiving area of the warehouse (bin null). */
    public record Place(Long binId) {
        public static final Place RECEIVING = new Place(null);

        public static Place bin(long binId) {
            return new Place(binId);
        }

        boolean receiving() {
            return binId == null;
        }
    }

    /**
     * One move of base units. from null = stock arrives (receive, adjustment up); to null = stock leaves
     * (adjustment down, shipping). batchNo / expiry identify the lot and must match on both sides.
     */
    public record Move(Kind type, long warehouseId, long productId, String batchNo, LocalDate expiry,
            Place from, Place to, BigDecimal qty, String reason, String refType, Long refId, long userId,
            String clientRef) {

        /** A move the server makes itself (no phone retry to guard against). */
        public Move(Kind type, long warehouseId, long productId, String batchNo, LocalDate expiry, Place from, Place to,
                BigDecimal qty, String reason, String refType, Long refId, long userId) {
            this(type, warehouseId, productId, batchNo, expiry, from, to, qty, reason, refType, refId, userId, null);
        }
    }

    /** Published after each move (live 3D views refresh fill levels once the transaction commits). */
    public record StockChanged(long warehouseId) {
    }

    /** What move() did: MOVED, or ALREADY_DONE for a retried Idempotency-Key. */
    public enum Outcome { MOVED, ALREADY_DONE }

    /** What one document line needs, in base units; label names it in a shortage message (e.g. "RICE-5KG (BAG)"). */
    public record Need(int line, long productId, BigDecimal qty, String label) {
    }

    /** Where the stock of a line was set aside. */
    public record Allocation(int line, long productId, long binId, String binCode, String batchNo, LocalDate expiry, BigDecimal qty) {
    }

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final StockMovementRepository ledger;
    private final BinService bins;
    private final ProductService products;
    private final org.springframework.context.ApplicationEventPublisher events;

    public StockMovementService(StockRepository stock, ReservationRepository reservations, StockMovementRepository ledger, BinService bins, ProductService products,
            org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.stock = stock;
        this.reservations = reservations;
        this.ledger = ledger;
        this.bins = bins;
        this.products = products;
    }

    @Transactional
    public Outcome move(Move m) {
        if (m.clientRef() != null && ledger.existsByClientRef(m.clientRef())) {
            return Outcome.ALREADY_DONE;
        }
        if (m.from() == null && m.to() == null) {
            throw new IllegalArgumentException("a move needs a from or a to");
        }
        BigDecimal qty = m.qty().setScale(3, RoundingMode.UNNECESSARY);
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (m.from() != null && m.from().equals(m.to())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_PLACE", "Pick a different bin to move to");
        }
        long tenantId = TenantContext.require();
        String batch = m.batchNo() == null || m.batchNo().isBlank() ? null : m.batchNo().trim();

        // 1. The target bin first (one global order: bin, then stock rows).
        BinService.BinRef bin = null;
        if (m.to() != null && !m.to().receiving()) {
            bin = bins.lockForStock(m.to().binId());
            if (bin.warehouseId() != m.warehouseId()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BIN_OTHER_WAREHOUSE", "That bin is in another warehouse");
            }
            if (bin.blocked()) {
                throw new ApiException(HttpStatus.CONFLICT, "BIN_BLOCKED", "Bin " + bin.code() + " is blocked");
            }
        }

        // 2. Stock rows on both sides, created if missing, locked in bin order.
        List<Place> sides = new ArrayList<>();
        if (m.from() != null) {
            sides.add(m.from());
        }
        if (m.to() != null) {
            sides.add(m.to());
        }
        sides.sort(Comparator.comparing((Place p) -> p.binId() == null ? 0L : p.binId()));
        Instant now = Instant.now();
        Stock from = null;
        Stock to = null;
        for (Place p : sides) {
            stock.ensure(tenantId, m.warehouseId(), p.binId(), m.productId(), batch, m.expiry(), now);
            Stock row = stock.lockPlace(tenantId, m.warehouseId(), p.binId(), m.productId(), batch, m.expiry()).orElseThrow();
            if (p.equals(m.from())) {
                from = row;
            } else {
                to = row;
            }
        }

        // 3. Enough to take?
        if (from != null) {
            if (from.available().compareTo(qty) < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_STOCK", "Only "
                        + from.available().stripTrailingZeros().toPlainString() + " available there");
            }
            from.take(qty);
        }
        // 4. Room in the target bin? (after the row locks, so every figure read is current)
        if (bin != null) {
            checkCapacity(bin, m.productId(), qty);
        }
        if (to != null) {
            to.add(qty);
        }
        stock.flush();
        if (from != null && from.isEmpty()) {
            stock.delete(from); // an empty place keeps no row; the ledger keeps the history
        }

        // 5. The ledger line.
        ledger.save(new StockMovement(StockMovement.Type.valueOf(m.type().name()), m.warehouseId(), m.productId(), batch, m.expiry(),
                m.from() == null ? null : m.from().binId(), m.from() != null && m.from().receiving(),
                m.to() == null ? null : m.to().binId(), m.to() != null && m.to().receiving(), qty,
                blankToNull(m.reason()), m.refType(), m.refId(), m.clientRef(), m.userId()));
        try {
            ledger.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // the same key arrived twice at once: the other request made the move
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_DONE", "This was just done; reload to see it");
        }
        events.publishEvent(new StockChanged(m.warehouseId()));
        return Outcome.MOVED;
    }

    /**
     * Weight and volume already in the bin plus what arrives must fit (a 0 capacity or unknown size is not checked).
     * Reads the bin's stock with a locking read of plain values: no stale snapshot, and no entity copies that
     * would shadow the rows this move has locked.
     */
    private void checkCapacity(BinService.BinRef bin, long productId, BigDecimal qty) {
        List<Object[]> inBin = stock.contentsForUpdate(bin.id());
        List<Long> ids = new ArrayList<>(inBin.stream().map(row -> ((Number) row[0]).longValue()).distinct().toList());
        if (!ids.contains(productId)) {
            ids.add(productId);
        }
        Map<Long, ProductService.Measure> sizes = products.measures(ids);
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        for (Object[] row : inBin) {
            ProductService.Measure ms = sizes.get(((Number) row[0]).longValue());
            BigDecimal onHand = (BigDecimal) row[1];
            weight = weight.add(times(onHand, ms == null ? null : ms.weightG()));
            volume = volume.add(times(onHand, ms == null ? null : ms.volumeCm3()));
        }
        ProductService.Measure incoming = sizes.get(productId);
        BigDecimal addWeight = times(qty, incoming == null ? null : incoming.weightG());
        BigDecimal addVolume = times(qty, incoming == null ? null : incoming.volumeCm3());
        boolean tooHeavy = bin.maxWeightG() > 0 && weight.add(addWeight).compareTo(BigDecimal.valueOf(bin.maxWeightG())) > 0;
        boolean tooBig = bin.maxVolumeCm3() > 0 && volume.add(addVolume).compareTo(BigDecimal.valueOf(bin.maxVolumeCm3())) > 0;
        if (tooHeavy || tooBig) {
            throw new ApiException(HttpStatus.CONFLICT, "BIN_FULL", "Bin " + bin.code() + " has no room for that ("
                    + (tooHeavy ? "weight" : "volume") + ")");
        }
    }

    /**
     * Sets stock aside for every line of a document, oldest receipt first (FIFO), all or nothing. Only stock in
     * bins that are not blocked counts; the receiving area never does. Rows are locked one by one in bin order,
     * the same order move() uses, so a release and a transfer cannot deadlock; amounts are read after the locks.
     * When any line is short nothing is reserved and NOT_ENOUGH_STOCK lists every shortage.
     */
    @Transactional
    public List<Allocation> reserve(long warehouseId, String refType, long refId, List<Need> needs, long userId) {
        List<Long> productIds = needs.stream().map(Need::productId).distinct().toList();
        List<Stock> rows = new ArrayList<>();
        for (Long id : stock.reservableIds(warehouseId, productIds)) {
            stock.lockById(id).filter(s -> s.available().signum() > 0).ifPresent(rows::add);
        }
        Map<Long, BinService.BinLabel> labels = bins.labels(rows.stream().map(Stock::getBinId).distinct().toList());
        rows.removeIf(s -> labels.get(s.getBinId()) == null || labels.get(s.getBinId()).blocked());
        rows.sort(Comparator.comparing(Stock::getReceivedAt).thenComparing(s -> labels.get(s.getBinId()).code()));

        Map<Stock, BigDecimal> free = new java.util.LinkedHashMap<>();
        rows.forEach(r -> free.put(r, r.available()));
        List<Allocation> out = new ArrayList<>();
        Map<Allocation, Stock> placeOf = new java.util.IdentityHashMap<>();
        List<String> shortages = new ArrayList<>();
        for (Need n : needs) {
            BigDecimal want = n.qty().setScale(3, RoundingMode.UNNECESSARY);
            BigDecimal left = want;
            for (Map.Entry<Stock, BigDecimal> e : free.entrySet()) {
                if (left.signum() == 0) {
                    break;
                }
                Stock s = e.getKey();
                if (s.getProductId() != n.productId() || e.getValue().signum() == 0) {
                    continue;
                }
                BigDecimal take = left.min(e.getValue());
                e.setValue(e.getValue().subtract(take));
                left = left.subtract(take);
                Allocation a = new Allocation(n.line(), n.productId(), s.getBinId(), labels.get(s.getBinId()).code(),
                        s.getBatchNo(), s.getExpiryDate(), take);
                out.add(a);
                placeOf.put(a, s);
            }
            if (left.signum() > 0) {
                shortages.add(n.label() + ": need " + plain(want) + ", " + plain(want.subtract(left)) + " free in bins");
            }
        }
        if (!shortages.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_STOCK", String.join("; ", shortages));
        }
        for (Allocation a : out) {
            Stock s = placeOf.get(a);
            s.reserve(a.qty());
            reservations.save(new Reservation(s, a.qty(), refType, refId, a.line(), userId));
        }
        stock.flush();
        events.publishEvent(new StockChanged(warehouseId));
        return out;
    }

    /** Frees everything a document has set aside (rows locked in bin order). Doing it twice changes nothing. */
    @Transactional
    public void unreserve(String refType, long refId) {
        List<Reservation> held = reservations.findByRefTypeAndRefIdOrderByRefLineAscIdAsc(refType, refId);
        if (held.isEmpty()) {
            return;
        }
        long tenantId = TenantContext.require();
        List<Reservation> sorted = new ArrayList<>(held);
        sorted.sort(Comparator.comparing(Reservation::getBinId));
        for (Reservation r : sorted) {
            stock.lockPlace(tenantId, r.getWarehouseId(), r.getBinId(), r.getProductId(), r.getBatchNo(), r.getExpiryDate())
                    .ifPresent(s -> s.unreserve(r.getQty()));
        }
        reservations.deleteAll(held);
        stock.flush();
        events.publishEvent(new StockChanged(held.get(0).getWarehouseId()));
    }

    /** What a document has set aside, line by line. */
    @Transactional(readOnly = true)
    public List<Allocation> reserved(String refType, long refId) {
        List<Reservation> held = reservations.findByRefTypeAndRefIdOrderByRefLineAscIdAsc(refType, refId);
        Map<Long, BinService.BinLabel> labels = bins.labels(held.stream().map(Reservation::getBinId).distinct().toList());
        return held.stream().map(r -> new Allocation(r.getRefLine(), r.getProductId(), r.getBinId(),
                labels.containsKey(r.getBinId()) ? labels.get(r.getBinId()).code() : null, r.getBatchNo(), r.getExpiryDate(), r.getQty()))
                .toList();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal times(BigDecimal qty, Integer perUnit) {
        return perUnit == null ? BigDecimal.ZERO : qty.multiply(BigDecimal.valueOf(perUnit));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
