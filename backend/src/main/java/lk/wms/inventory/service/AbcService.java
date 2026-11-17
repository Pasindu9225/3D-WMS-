package lk.wms.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.inventory.repo.StockMovementRepository;
import lk.wms.tenant.service.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ABC classes (v1.1 task 4): products picked most often are A (together 80% of the pick lines of the last 90 days),
 * the next ones up to 95% are B, the rest (and never-picked products) C. Count plans use them: count A often.
 */
@Service
public class AbcService {

    public record Result(int a, int b, int c) {
    }

    static final Duration WINDOW = Duration.ofDays(90);
    private static final Logger log = LoggerFactory.getLogger(AbcService.class);

    private final StockMovementRepository ledger;
    private final ProductService products;
    private final TenantService tenants;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public AbcService(StockMovementRepository ledger, ProductService products, TenantService tenants, AuditService audit,
            PlatformTransactionManager tm) {
        this.ledger = ledger;
        this.products = products;
        this.tenants = tenants;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    /** Sundays 02:00 server time, for every active company, each in its own transaction. */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void weekly() {
        for (Long tenantId : TenantContext.callAsSystem(tenants::activeIds)) {
            try {
                Result r = TenantContext.callAs(tenantId, () -> tx.execute(s -> classify(Instant.now())));
                log.info("ABC classes for company {}: {} A, {} B, {} C", tenantId, r.a(), r.b(), r.c());
            } catch (RuntimeException e) {
                log.warn("ABC classes failed for company {}", tenantId, e); // the next company still runs
            }
        }
    }

    /** "Recalculate now" for the user's own company. */
    public Result recalculate(CurrentUser cu) {
        return tx.execute(s -> {
            Result r = classify(Instant.now());
            audit.record(cu.tenantId(), cu.userId(), "ABC_RECALCULATED", "product", null, null,
                    Map.of("a", r.a(), "b", r.b(), "c", r.c()));
            return r;
        });
    }

    private Result classify(Instant now) {
        Map<Long, Long> picks = new HashMap<>();
        ledger.pickCounts(now.minus(WINDOW)).forEach(row -> picks.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        Map<Long, String> classes = classes(products.liveIds(), picks);
        products.setAbc(classes, now);
        int a = 0;
        int b = 0;
        for (String c : classes.values()) {
            a += c.equals("A") ? 1 : 0;
            b += c.equals("B") ? 1 : 0;
        }
        return new Result(a, b, classes.size() - a - b);
    }

    /**
     * The class of every product: by picks, most first (ties by id); a product is A while the picks before it are
     * under 80% of all picks, B under 95%, otherwise C. Products with no picks are C.
     */
    static Map<Long, String> classes(List<Long> productIds, Map<Long, Long> picks) {
        long total = picks.values().stream().mapToLong(Long::longValue).sum();
        List<Long> picked = new ArrayList<>(productIds.stream().filter(id -> picks.getOrDefault(id, 0L) > 0).toList());
        picked.sort(Comparator.comparing((Long id) -> picks.get(id)).reversed().thenComparing(id -> id));
        Map<Long, String> out = new HashMap<>();
        productIds.forEach(id -> out.put(id, "C"));
        long before = 0;
        for (Long id : picked) {
            out.put(id, before * 100 < total * 80 ? "A" : before * 100 < total * 95 ? "B" : "C");
            before += picks.get(id);
        }
        return out;
    }
}
