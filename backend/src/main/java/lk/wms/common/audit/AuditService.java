package lk.wms.common.audit;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import lk.wms.common.web.CorrelationIdFilter;
import lk.wms.common.web.RequestInfo;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC-28: who did what, from where, with which correlation ID, and the record before and after.
 * Snapshots are records chosen by the caller; never pass an entity (it would carry password hashes).
 */
@Service
public class AuditService {

    /** One audit row as the API shows it. {@code userId} is internal; the caller maps it to a public ID. */
    public record Entry(Long id, Long userId, String action, String entity, String entityId,
            String beforeJson, String afterJson, String ip, String correlationId, Instant createdAt) {
    }

    private final EntityManager em;
    private final AuditLogRepository logs;
    private final JsonMapper json;

    public AuditService(EntityManager em, AuditLogRepository logs, JsonMapper json) {
        this.em = em;
        this.logs = logs;
        this.json = json;
    }

    /** Joins the caller's transaction, so the audit row commits or rolls back with the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long tenantId, Long userId, String action, String entity, String entityId) {
        record(tenantId, userId, action, entity, entityId, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long tenantId, Long userId, String action, String entity, String entityId,
            Object before, Object after) {
        String ip = null;
        String userAgent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            ip = RequestInfo.ip(request);
            userAgent = RequestInfo.userAgent(request);
        }
        em.persist(new AuditLog(tenantId, userId, action, entity, entityId, toJson(before), toJson(after),
                ip, userAgent, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    @Transactional(readOnly = true)
    public Page<Entry> search(long tenantId, String entity, Long userId, Instant from, Instant to, Pageable pageable) {
        return logs.search(tenantId, entity, userId, from, to, pageable).map(AuditLog::toEntry);
    }

    private String toJson(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
