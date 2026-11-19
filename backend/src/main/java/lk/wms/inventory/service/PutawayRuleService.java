package lk.wms.inventory.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.wms.catalog.service.ProductService;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.inventory.domain.PutawayRule;
import lk.wms.inventory.repo.PutawayRuleRepository;
import lk.wms.layout.service.BinService;
import lk.wms.layout.service.WarehouseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putaway rules (v1.2 task 4): managers say where goods go. A rule applies to one product, one category or every
 * product, and sends it to a fixed bin or into a zone (optionally only that zone). PutawayService reads
 * {@link #matching} when it suggests bins and when a worker scans one.
 */
@Service
public class PutawayRuleService {

    public enum AppliesTo { PRODUCT, CATEGORY, CLIENT, ALL }

    public record RuleView(String id, int priority, AppliesTo appliesTo, String sku, String productName, String category,
            String binCode, String zoneCode, boolean onlyZone, boolean active, int version, String clientCode) {
    }

    /** A rule as PutawayService uses it: a bin id or a zone code. */
    public record Match(Long binId, String zoneCode, boolean onlyZone) {
    }

    static final int MAX_RULES = 200;

    private final PutawayRuleRepository rules;
    private final WarehouseService warehouses;
    private final BinService bins;
    private final ProductService products;
    private final AuditService audit;
    private final lk.wms.catalog.service.PartnerService partners;

    public PutawayRuleService(PutawayRuleRepository rules, WarehouseService warehouses, BinService bins, ProductService products,
            AuditService audit, lk.wms.catalog.service.PartnerService partners) {
        this.partners = partners;
        this.rules = rules;
        this.warehouses = warehouses;
        this.bins = bins;
        this.products = products;
        this.audit = audit;
    }

    /**
     * Active rules for this product: its own rules first, then its category's, then its client's (v2.1, 3PL), then the
     * ones for every product; each by priority. ownerId: the product's client, null for the company's own goods.
     */
    @Transactional(readOnly = true)
    public List<Match> matching(long warehouseId, long productId, String category, Long ownerId) {
        return rules.findByWarehouseIdOrderByPriorityAscIdAsc(warehouseId).stream()
                .filter(r -> r.isActive() && (r.getProductId() != null ? r.getProductId() == productId
                        : r.getCategory() != null ? category != null && r.getCategory().equalsIgnoreCase(category.trim())
                        : r.getOwnerId() == null || r.getOwnerId().equals(ownerId)))
                .sorted(Comparator.comparingInt(PutawayRuleService::kindRank)) // stable: priority order kept within a kind
                .map(r -> new Match(r.getBinId(), r.getZoneCode(), r.isOnlyZone())).toList();
    }

    private static int kindRank(PutawayRule r) {
        return r.getProductId() != null ? 0 : r.getCategory() != null ? 1 : r.getOwnerId() != null ? 2 : 3;
    }

    @Transactional(readOnly = true)
    public List<RuleView> list(CurrentUser cu, String warehouseId) {
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        return views(rules.findByWarehouseIdOrderByPriorityAscIdAsc(wh.id()));
    }

    /** sku, category or clientCode (or none = every product); binCode or zoneCode (exactly one). */
    @Transactional
    public RuleView create(CurrentUser cu, String warehouseId, int priority, String sku, String category, String clientCode, String binCode,
            String zoneCode, boolean onlyZone) {
        clientCode = blankToNull(clientCode);
        WarehouseService.WarehouseRef wh = warehouses.requireAccess(cu, warehouseId);
        sku = blankToNull(sku);
        category = blankToNull(category);
        binCode = blankToNull(binCode);
        zoneCode = blankToNull(zoneCode);
        if ((sku != null ? 1 : 0) + (category != null ? 1 : 0) + (clientCode != null ? 1 : 0) > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RULE_TARGET", "A rule is for one product, one category or one client");
        }
        if ((binCode == null) == (zoneCode == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RULE_DESTINATION", "Choose a bin or a zone");
        }
        if (rules.findByWarehouseIdOrderByPriorityAscIdAsc(wh.id()).size() >= MAX_RULES) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_LIMIT", "A warehouse can have up to " + MAX_RULES + " putaway rules");
        }
        Long productId = sku == null ? null : products.basic(products.lookup(sku).product().id()).id();
        Long binId = null;
        if (binCode != null) {
            binId = bins.byCode(cu, wh.id(), binCode.toUpperCase(Locale.ROOT)).id();
        } else {
            zoneCode = zoneCode.toUpperCase(Locale.ROOT);
            if (!bins.zoneCodes(wh.id()).contains(zoneCode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ZONE_NOT_FOUND", "There is no zone " + zoneCode + " in the published layout");
            }
        }
        PutawayRule rule = new PutawayRule(wh.id(), priority, productId, category, binId, zoneCode, onlyZone && zoneCode != null, cu.userId());
        rule.setOwnerId(clientCode == null ? null : partners.clientByCode(clientCode.toUpperCase(Locale.ROOT)).id());
        PutawayRule r = rules.saveAndFlush(rule);
        RuleView v = views(List.of(r)).get(0);
        audit.record(cu.tenantId(), cu.userId(), "PUTAWAY_RULE_CREATED", "putaway_rule", r.getPublicId(), null, v);
        return v;
    }

    @Transactional
    public RuleView update(CurrentUser cu, String publicId, int version, int priority, boolean onlyZone, boolean active) {
        PutawayRule r = visible(cu, publicId);
        if (r.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_CHANGED", "Someone else changed this rule; reload it");
        }
        RuleView before = views(List.of(r)).get(0);
        r.set(priority, onlyZone, active);
        rules.flush();
        RuleView after = views(List.of(r)).get(0);
        audit.record(cu.tenantId(), cu.userId(), "PUTAWAY_RULE_UPDATED", "putaway_rule", r.getPublicId(), before, after);
        return after;
    }

    @Transactional
    public void delete(CurrentUser cu, String publicId) {
        PutawayRule r = visible(cu, publicId);
        RuleView before = views(List.of(r)).get(0);
        rules.delete(r);
        audit.record(cu.tenantId(), cu.userId(), "PUTAWAY_RULE_DELETED", "putaway_rule", r.getPublicId(), before, null);
    }

    private PutawayRule visible(CurrentUser cu, String publicId) {
        PutawayRule r = rules.findByPublicId(publicId).orElse(null);
        List<Long> ids = r == null ? null : warehouses.assignedIds(cu);
        if (r == null || (ids != null && !ids.contains(r.getWarehouseId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "Putaway rule not found");
        }
        return r;
    }

    private List<RuleView> views(List<PutawayRule> list) {
        Map<Long, ProductService.Basic> basics = products.basics(new HashSet<>(list.stream().map(PutawayRule::getProductId)
                .filter(java.util.Objects::nonNull).toList()));
        Map<Long, BinService.BinLabel> labels = new HashMap<>(bins.labels(list.stream().map(PutawayRule::getBinId)
                .filter(java.util.Objects::nonNull).distinct().toList()));
        Map<Long, lk.wms.catalog.service.PartnerService.ClientRef> clients = partners.clients(new HashSet<>(list.stream()
                .map(PutawayRule::getOwnerId).filter(java.util.Objects::nonNull).toList()));
        return list.stream().map(r -> {
            ProductService.Basic p = r.getProductId() == null ? null : basics.get(r.getProductId());
            BinService.BinLabel b = r.getBinId() == null ? null : labels.get(r.getBinId());
            AppliesTo to = r.getProductId() != null ? AppliesTo.PRODUCT : r.getCategory() != null ? AppliesTo.CATEGORY
                    : r.getOwnerId() != null ? AppliesTo.CLIENT : AppliesTo.ALL;
            lk.wms.catalog.service.PartnerService.ClientRef c = r.getOwnerId() == null ? null : clients.get(r.getOwnerId());
            return new RuleView(r.getPublicId(), r.getPriority(), to, p == null ? null : p.sku(), p == null ? null : p.name(), r.getCategory(),
                    b == null ? null : b.code(), r.getZoneCode(), r.isOnlyZone(), r.isActive(), r.getVersion(), c == null ? null : c.code());
        }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
