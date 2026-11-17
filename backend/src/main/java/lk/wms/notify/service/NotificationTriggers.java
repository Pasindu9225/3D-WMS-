package lk.wms.notify.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.service.StockMovementService;
import lk.wms.inventory.service.StockService;
import lk.wms.layout.service.WarehouseService;
import lk.wms.notify.domain.Notification;
import lk.wms.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What makes warehouse alerts: low stock right after a move out commits, expiry once a day. Each runs in its own
 * transaction, so a failure here never undoes a pick, and never stops the next company in the job.
 */
@Component
public class NotificationTriggers {

    static final int EXPIRY_DAYS = 30;
    private static final Logger log = LoggerFactory.getLogger(NotificationTriggers.class);

    private final NotificationService notifications;
    private final ProductService products;
    private final StockService stock;
    private final WarehouseService warehouses;
    private final TenantService tenants;
    private final TransactionTemplate tx;

    public NotificationTriggers(NotificationService notifications, ProductService products, StockService stock,
            WarehouseService warehouses, TenantService tenants, PlatformTransactionManager tm) {
        this.notifications = notifications;
        this.products = products;
        this.stock = stock;
        this.warehouses = warehouses;
        this.tenants = tenants;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stock left a bin: at or below the reorder point in that warehouse? Once per product, warehouse and day. */
    @TransactionalEventListener
    void onStockTaken(StockMovementService.StockTaken e) {
        try {
            tx.executeWithoutResult(s -> lowStock(e.warehouseId(), e.productId()));
        } catch (RuntimeException ex) {
            log.warn("low stock notification failed for product {}", e.productId(), ex);
        }
    }

    void lowStock(long warehouseId, long productId) {
        ProductService.Watch p = products.watch(productId);
        if (p == null || p.reorderPoint() == null) {
            return;
        }
        // ponytail: totals the whole warehouse per move; a per-product sum query if moves get heavy
        BigDecimal onHand = stock.onHandByProduct(warehouseId).getOrDefault(productId, BigDecimal.ZERO);
        if (onHand.compareTo(p.reorderPoint()) > 0) {
            return;
        }
        notifications.toWatchers(warehouseId, Notification.Kind.LOW_STOCK,
                Map.of("sku", p.sku(), "name", p.name(), "onHand", onHand.stripTrailingZeros().toPlainString(),
                        "reorderPoint", p.reorderPoint().stripTrailingZeros().toPlainString(), "uom", p.baseUom(),
                        "warehouse", String.valueOf(warehouses.codeOf(warehouseId))),
                "/app/dashboard", "LOW:" + warehouseId + ":" + productId + ":" + LocalDate.now());
    }

    /** Every day 06:00: lots expired or expiring within 30 days, one summary per warehouse; old notifications removed. */
    @Scheduled(cron = "0 0 6 * * *")
    public void daily() {
        for (Long tenantId : TenantContext.callAsSystem(tenants::activeIds)) {
            try {
                TenantContext.callAs(tenantId, () -> {
                    tx.executeWithoutResult(s -> notifications.deleteOld());
                    for (long wh : warehouses.liveIds()) {
                        tx.executeWithoutResult(s -> expiry(wh, LocalDate.now()));
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("daily notifications failed for company {}", tenantId, e);
            }
        }
    }

    /** Returns how many people were told. */
    public int expiry(long warehouseId, LocalDate today) {
        List<StockService.ExpiringLot> lots = stock.expiringBefore(warehouseId, today.plusDays(EXPIRY_DAYS + 1), 1000);
        long expired = lots.stream().filter(l -> l.expiry().isBefore(today)).count();
        long soon = lots.size() - expired;
        if (lots.isEmpty()) {
            return 0;
        }
        return notifications.toWatchers(warehouseId, Notification.Kind.EXPIRY,
                Map.of("expired", expired, "soon", soon, "days", EXPIRY_DAYS, "warehouse", String.valueOf(warehouses.codeOf(warehouseId))),
                "/app/dashboard", "EXPIRY:" + warehouseId + ":" + today);
    }
}
