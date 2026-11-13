package lk.wms.layout.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.LayoutVersion;
import lk.wms.layout.domain.Warehouse;
import lk.wms.layout.repo.LayoutVersionRepository;
import lk.wms.layout.repo.WarehouseRepository;
import lk.wms.layout.service.LayoutValidator.Issue;
import lk.wms.layout.service.LayoutValidator.Severity;
import lk.wms.layout.service.RackTemplateService.TemplateView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The layout draft of a warehouse: read, check, save. One draft per warehouse; saves carry the
 * revision they were based on, so two planners cannot silently overwrite each other.
 */
@Service
public class LayoutService {

    /**
     * What the editor gets: the layout, its revision (0 = no draft saved yet), where it came from,
     * the rack types it uses (so the editor can draw them) and the current warnings.
     */
    public record LayoutView(String status, int revision, LayoutDocument layout, Map<String, TemplateView> templates,
            List<Issue> warnings, Instant updatedAt) {
    }

    private final WarehouseRepository warehouses;
    private final WarehouseService warehouseAccess;
    private final LayoutVersionRepository versions;
    private final RackTemplateService templates;
    private final AuditService audit;
    private final JsonMapper json;

    public LayoutService(WarehouseRepository warehouses, WarehouseService warehouseAccess, LayoutVersionRepository versions,
            RackTemplateService templates, AuditService audit, JsonMapper json) {
        this.warehouses = warehouses;
        this.warehouseAccess = warehouseAccess;
        this.versions = versions;
        this.templates = templates;
        this.audit = audit;
        this.json = json;
    }

    /** The draft if there is one, else the last published layout, else a blank one on the warehouse floor. */
    @Transactional(readOnly = true)
    public LayoutView get(CurrentUser cu, String warehouseId) {
        WarehouseService.WarehouseRef ref = warehouseAccess.requireAccess(cu, warehouseId);
        LayoutVersion draft = versions.findFirstByWarehouseIdAndStatus(ref.id(), LayoutVersion.Status.DRAFT).orElse(null);
        if (draft != null) {
            return view("DRAFT", draft.getRevision(), parse(draft.getLayout()), draft.getUpdatedAt());
        }
        LayoutVersion published = versions.findFirstByWarehouseIdAndStatusOrderByPublishedAtDesc(ref.id(),
                LayoutVersion.Status.PUBLISHED).orElse(null);
        if (published != null) {
            return view("PUBLISHED", 0, parse(published.getLayout()), published.getUpdatedAt());
        }
        Warehouse w = warehouses.findByPublicId(warehouseId).orElseThrow();
        return view("NEW", 0, blank(w), w.getCreatedAt());
    }

    /**
     * Saves the whole draft. {@code baseRevision} is the revision the editor started from (0 for the
     * first draft); if someone saved since, 409 LAYOUT_CHANGED. Errors refuse the save (400 LAYOUT_INVALID
     * with every issue listed); warnings are saved and returned.
     */
    @Transactional
    public LayoutView saveDraft(CurrentUser cu, String warehouseId, int baseRevision, LayoutDocument doc) {
        warehouseAccess.requireAccess(cu, warehouseId); // 404 for no access, before taking any lock
        Warehouse w = warehouses.lockByPublicId(warehouseId).orElseThrow(); // one save at a time per warehouse
        LayoutVersion draft = versions.findFirstByWarehouseIdAndStatus(w.getId(), LayoutVersion.Status.DRAFT).orElse(null);
        int current = draft == null ? 0 : draft.getRevision();
        if (baseRevision != current) {
            throw new ApiException(HttpStatus.CONFLICT, "LAYOUT_CHANGED",
                    "Someone else saved this layout. Reload to see their changes.");
        }
        List<Issue> issues = LayoutValidator.validate(doc,
                templates.footprints(doc.racks().stream().map(LayoutDocument.Rack::templateId).distinct().toList()));
        List<Issue> errors = issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAYOUT_INVALID",
                    errors.size() + " problem(s) must be fixed before saving", errors);
        }
        String body = json.writeValueAsString(doc);
        if (draft == null) {
            draft = versions.save(LayoutVersion.newDraft(w.getId(), body, cu.userId()));
        } else {
            draft.saveDraft(body, cu.userId());
        }
        audit.record(cu.tenantId(), cu.userId(), "LAYOUT_DRAFT_SAVED", "warehouse", w.getPublicId(), null,
                Map.of("revision", draft.getRevision(), "zones", doc.zones().size(), "racks", doc.racks().size(),
                        "warnings", issues.size()));
        return new LayoutView("DRAFT", draft.getRevision(), doc, templateViews(doc), issues, draft.getUpdatedAt());
    }

    private LayoutView view(String status, int revision, LayoutDocument doc, Instant updatedAt) {
        List<Issue> warnings = LayoutValidator.validate(doc,
                templates.footprints(doc.racks().stream().map(LayoutDocument.Rack::templateId).distinct().toList()));
        return new LayoutView(status, revision, doc, templateViews(doc), warnings, updatedAt);
    }

    private Map<String, TemplateView> templateViews(LayoutDocument doc) {
        return templates.views(doc.racks().stream().map(LayoutDocument.Rack::templateId).collect(Collectors.toSet()));
    }

    private LayoutDocument parse(String body) {
        return json.readValue(body, LayoutDocument.class);
    }

    private LayoutDocument blank(Warehouse w) {
        int[][] points = json.readValue(w.getFloorPolygon(), int[][].class);
        List<LayoutDocument.Point> floor = java.util.Arrays.stream(points)
                .map(p -> new LayoutDocument.Point(p[0], p[1])).toList();
        return new LayoutDocument(floor, List.of(), List.of(), List.of(), LayoutDocument.Settings.DEFAULT);
    }
}
