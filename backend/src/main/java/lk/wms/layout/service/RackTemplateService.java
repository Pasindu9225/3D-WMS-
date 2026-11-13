package lk.wms.layout.service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.layout.domain.RackTemplate;
import lk.wms.layout.repo.RackTemplateRepository;
import lk.wms.layout.service.LayoutValidator.Footprint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The company's rack type library. Shared by all its warehouses, so no warehouse access check applies. */
@Service
public class RackTemplateService {

    public record TemplateView(String id, String code, String name, int widthMm, int depthMm, int heightMm, int levels,
            int binsPerLevel, long maxWeightPerLevelG) {
    }

    /** Fields the planner sets; code is fixed once created (racks refer to the template by ID, labels by code). */
    public record TemplateSpec(String name, int widthMm, int depthMm, int heightMm, int levels, int binsPerLevel,
            long maxWeightPerLevelG) {
    }

    private final RackTemplateRepository templates;
    private final AuditService audit;

    public RackTemplateService(RackTemplateRepository templates, AuditService audit) {
        this.templates = templates;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<TemplateView> list(Pageable pageable) {
        return templates.findAllBy(pageable).map(RackTemplateService::view);
    }

    @Transactional
    public TemplateView create(CurrentUser cu, String code, TemplateSpec s) {
        checkHeight(s);
        RackTemplate t;
        try {
            t = templates.saveAndFlush(new RackTemplate(code, s.name().trim(), s.widthMm(), s.depthMm(), s.heightMm(),
                    s.levels(), s.binsPerLevel(), s.maxWeightPerLevelG()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_IN_USE", "Another rack type already uses this code");
        }
        audit.record(cu.tenantId(), cu.userId(), "RACK_TEMPLATE_CREATED", "rack_template", t.getPublicId(), null, view(t));
        return view(t);
    }

    @Transactional
    public TemplateView update(CurrentUser cu, String publicId, TemplateSpec s) {
        checkHeight(s);
        RackTemplate t = find(publicId);
        TemplateView before = view(t);
        t.set(s.name().trim(), s.widthMm(), s.depthMm(), s.heightMm(), s.levels(), s.binsPerLevel(), s.maxWeightPerLevelG());
        audit.record(cu.tenantId(), cu.userId(), "RACK_TEMPLATE_UPDATED", "rack_template", t.getPublicId(), before, view(t));
        return view(t);
    }

    /** Racks already placed keep their own copy of the size, so archiving never breaks a layout. */
    @Transactional
    public void archive(CurrentUser cu, String publicId) {
        RackTemplate t = find(publicId);
        t.archive();
        audit.record(cu.tenantId(), cu.userId(), "RACK_TEMPLATE_ARCHIVED", "rack_template", t.getPublicId(), view(t), null);
    }

    /** Footprints by public ID, for the layout check. Unknown or archived IDs are simply absent. */
    @Transactional(readOnly = true)
    public Map<String, Footprint> footprints(Collection<String> publicIds) {
        return templates.findByPublicIdIn(publicIds).stream()
                .collect(Collectors.toMap(RackTemplate::getPublicId, t -> new Footprint(t.getWidthMm(), t.getDepthMm())));
    }

    /** Entities by public ID, for publish (same module). */
    @Transactional(readOnly = true)
    public Map<String, RackTemplate> entities(Collection<String> publicIds) {
        return templates.findByPublicIdIn(publicIds).stream()
                .collect(Collectors.toMap(RackTemplate::getPublicId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Map<String, TemplateView> views(Collection<String> publicIds) {
        return templates.findByPublicIdIn(publicIds).stream().map(RackTemplateService::view)
                .collect(Collectors.toMap(TemplateView::id, Function.identity()));
    }

    private RackTemplate find(String publicId) {
        return templates.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RACK_TEMPLATE_NOT_FOUND", "Rack type not found"));
    }

    /** Each level needs at least 250 mm of clear height for a box and a hand. */
    private static void checkHeight(TemplateSpec s) {
        if (s.heightMm() / s.levels() < 250) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEVELS_TOO_LOW",
                    "Too many levels for this height: each level needs at least 250 mm");
        }
    }

    private static TemplateView view(RackTemplate t) {
        return new TemplateView(t.getPublicId(), t.getCode(), t.getName(), t.getWidthMm(), t.getDepthMm(), t.getHeightMm(),
                t.getLevels(), t.getBinsPerLevel(), t.getMaxWeightPerLevelG());
    }
}
