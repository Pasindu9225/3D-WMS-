package lk.wms.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.service.StockService;
import lk.wms.inventory.service.StockService.Moved;
import lk.wms.layout.service.SceneService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.outbound.service.SalesOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's morning page (v1.0 task 7): stock value, low stock, today's flow, bin use and two floor KPIs, for
 * one warehouse. It only reads, through other modules' services. Each figure is one small query.
 * ponytail: computed on every request; cache for a minute if a large company makes it slow.
 */
@Service
public class DashboardService {

    public record LowStock(String productId, String sku, String name, String baseUom, BigDecimal onHand, BigDecimal reorderPoint) {
    }

    public record Today(long receipts, BigDecimal unitsReceived, long shipments, BigDecimal unitsShipped, long ordersReleased,
            long ordersShipped, long adjustments) {
    }

    public record Dashboard(long stockValueMinor, int productsWithoutCost, List<LowStock> lowStock, Today today,
            int bins, int binsInUse, int binUsePercent, int averageFillPercent, Integer dockToStockMinutes, BigDecimal linesPerHour) {
    }

    // ponytail: Sri Lanka day and week; a per-company time zone when a customer outside it signs up.
    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final WarehouseService warehouses;
    private final StockService stock;
    private final ProductService products;
    private final SalesOrderService orders;
    private final SceneService scenes;

    public DashboardService(WarehouseService warehouses, StockService stock, ProductService products, SalesOrderService orders,
            SceneService scenes) {
        this.warehouses = warehouses;
        this.stock = stock;
        this.products = products;
        this.orders = orders;
        this.scenes = scenes;
    }

    @Transactional(readOnly = true)
    public Dashboard build(CurrentUser cu, String warehousePublicId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehousePublicId);
        Instant now = Instant.now();
        Instant today = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant weekAgo = now.minus(Duration.ofDays(7));

        // stock value and low stock
        Map<Long, BigDecimal> onHand = stock.onHandByProduct(wh.id());
        Map<Long, BigDecimal> points = products.reorderPoints();
        Set<Long> ids = new HashSet<>(onHand.keySet());
        ids.addAll(points.keySet());
        Map<Long, ProductService.Basic> basics = products.basics(ids);
        long value = 0;
        int noCost = 0;
        for (Map.Entry<Long, BigDecimal> e : onHand.entrySet()) {
            ProductService.Basic b = basics.get(e.getKey());
            if (b == null || b.costMinor() == null) {
                noCost++;
            } else {
                value += e.getValue().multiply(BigDecimal.valueOf(b.costMinor())).setScale(0, RoundingMode.HALF_UP).longValueExact();
            }
        }
        List<LowStock> low = new ArrayList<>();
        points.forEach((id, point) -> {
            BigDecimal have = onHand.getOrDefault(id, BigDecimal.ZERO);
            ProductService.Basic b = basics.get(id);
            if (b != null && have.compareTo(point) <= 0) {
                low.add(new LowStock(b.publicId(), b.sku(), b.name(), b.baseUom(), have, point));
            }
        });
        low.sort(Comparator.comparing(LowStock::sku));

        // today
        List<Moved> todays = stock.movedSince(wh.id(), List.of("RECEIVE", "SHIP", "ADJUST"), today);
        SalesOrderService.DayCounts counts = orders.countsSince(wh.id(), today);
        Today t = new Today(distinctRefs(todays, "RECEIVE"), sum(todays, "RECEIVE"), distinctRefs(todays, "SHIP"), sum(todays, "SHIP"),
                counts.released(), counts.shipped(), todays.stream().filter(m -> m.type().equals("ADJUST")).count());

        // bins: in use = holding anything (fill >= 1), of the bins not blocked
        int bins = 0;
        int inUse = 0;
        int fillSum = 0;
        try {
            SceneService.StatusView status = scenes.status(cu, warehousePublicId);
            for (int i = 0; i < status.fill().length; i++) {
                boolean blocked = ((status.flags().charAt(i) - '0') & 1) != 0;
                if (!blocked) {
                    bins++;
                    if (status.fill()[i] > 0) {
                        inUse++;
                        fillSum += status.fill()[i];
                    }
                }
            }
        } catch (lk.wms.common.errors.ApiException notPublished) {
            // no published layout yet: no bins to count
        }

        List<Moved> week = stock.movedSince(wh.id(), List.of("RECEIVE", "PUTAWAY", "PICK"), weekAgo.minus(Duration.ofDays(7)));
        return new Dashboard(value, noCost, low, t, bins, inUse, bins == 0 ? 0 : Math.round(inUse * 100f / bins),
                inUse == 0 ? 0 : Math.round((float) fillSum / inUse), dockToStock(week, weekAgo), linesPerHour(week, weekAgo));
    }

    /**
     * Average minutes goods waited in the receiving area before putaway, weighted by quantity: each putaway since
     * {@code from} takes the oldest received units of the same product and lot first.
     */
    static Integer dockToStock(List<Moved> moves, Instant from) {
        Map<String, Deque<BigDecimal[]>> waiting = new HashMap<>(); // [qty left, received at epoch seconds]
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        for (Moved m : moves) {
            String lot = m.productId() + "|" + Objects.toString(m.batchNo(), "") + "|" + Objects.toString(m.expiry(), "");
            if (m.type().equals("RECEIVE")) {
                waiting.computeIfAbsent(lot, k -> new ArrayDeque<>()).add(new BigDecimal[] {m.qty(), BigDecimal.valueOf(m.at().getEpochSecond())});
            } else if (m.type().equals("PUTAWAY")) {
                BigDecimal left = m.qty();
                Deque<BigDecimal[]> queue = waiting.getOrDefault(lot, new ArrayDeque<>());
                while (left.signum() > 0 && !queue.isEmpty()) {
                    BigDecimal[] head = queue.peek();
                    BigDecimal take = left.min(head[0]);
                    if (!m.at().isBefore(from)) {
                        BigDecimal minutes = BigDecimal.valueOf(m.at().getEpochSecond()).subtract(head[1]).divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);
                        weighted = weighted.add(minutes.multiply(take));
                        qty = qty.add(take);
                    }
                    head[0] = head[0].subtract(take);
                    left = left.subtract(take);
                    if (head[0].signum() == 0) {
                        queue.poll();
                    }
                }
            }
        }
        return qty.signum() == 0 ? null : weighted.divide(qty, 0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Pick lines confirmed per hour of picking, averaged over the days since {@code from} that had any picking. */
    static BigDecimal linesPerHour(List<Moved> moves, Instant from) {
        Map<LocalDate, List<Instant>> byDay = new TreeMap<>();
        moves.stream().filter(m -> m.type().equals("PICK") && !m.at().isBefore(from))
                .forEach(m -> byDay.computeIfAbsent(m.at().atZone(ZONE).toLocalDate(), d -> new ArrayList<>()).add(m.at()));
        if (byDay.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (List<Instant> picks : byDay.values()) {
            long seconds = Duration.between(picks.get(0), picks.get(picks.size() - 1)).getSeconds();
            BigDecimal hours = BigDecimal.valueOf(Math.max(seconds, 900)).divide(BigDecimal.valueOf(3600), 4, RoundingMode.HALF_UP); // at least 15 min
            total = total.add(BigDecimal.valueOf(picks.size()).divide(hours, 4, RoundingMode.HALF_UP));
        }
        return total.divide(BigDecimal.valueOf(byDay.size()), 1, RoundingMode.HALF_UP);
    }

    private static long distinctRefs(List<Moved> moves, String type) {
        return moves.stream().filter(m -> m.type().equals(type)).map(Moved::refId).filter(Objects::nonNull).distinct().count();
    }

    private static BigDecimal sum(List<Moved> moves, String type) {
        return moves.stream().filter(m -> m.type().equals(type)).map(Moved::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
