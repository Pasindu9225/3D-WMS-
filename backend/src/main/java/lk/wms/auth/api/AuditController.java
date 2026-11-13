package lk.wms.auth.api;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lk.wms.auth.service.UserService;
import lk.wms.auth.service.UserService.UserView;
import lk.wms.common.audit.AuditService;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SEC-28: the company's own audit trail. Lives in auth because it shows who (users) did what. */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {

    public record Actor(String id, String fullName) {
    }

    public record AuditRow(Instant at, String action, String entity, String entityId, Actor user, String ip,
            String correlationId, String before, String after) {
    }

    private final AuditService audit;
    private final UserService users;

    public AuditController(AuditService audit, UserService users) {
        this.audit = audit;
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public PageResponse<AuditRow> search(
            @RequestParam(required = false) @Size(max = 60) String entity,
            @RequestParam(required = false) @Size(max = 26) String user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        CurrentUser me = CurrentUser.require();
        var request = PageResponse.request(page, size, null, Map.of(),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Long userId = null;
        if (user != null && !user.isBlank()) {
            userId = users.internalId(user);
            if (userId == null) {
                return new PageResponse<>(java.util.List.of(), request.getPageNumber(), request.getPageSize(), 0);
            }
        }
        Page<AuditService.Entry> entries = audit.search(me.tenantId(), entity, userId, from, to, request);
        Set<Long> ids = new HashSet<>();
        entries.forEach(e -> ids.add(e.userId()));
        ids.remove(null);
        Map<Long, UserView> names = users.viewsByInternalId(ids);
        return PageResponse.of(entries, e -> {
            UserView u = e.userId() == null ? null : names.get(e.userId());
            return new AuditRow(e.createdAt(), e.action(), e.entity(), e.entityId(),
                    u == null ? null : new Actor(u.id(), u.fullName()), e.ip(), e.correlationId(),
                    e.beforeJson(), e.afterJson());
        });
    }

}
