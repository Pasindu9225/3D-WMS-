package lk.wms.common.audit;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import lk.wms.common.web.CorrelationIdFilter;
import lk.wms.common.web.RequestInfo;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * SEC-28: who did what, from where, with which correlation ID.
 * ponytail: no before/after JSON yet; task 6 adds it for business writes.
 */
@Service
public class AuditService {

    private final EntityManager em;

    public AuditService(EntityManager em) {
        this.em = em;
    }

    /** Joins the caller's transaction, so the audit row commits or rolls back with the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long tenantId, Long userId, String action, String entity, String entityId) {
        String ip = null;
        String userAgent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            ip = RequestInfo.ip(request);
            userAgent = RequestInfo.userAgent(request);
        }
        em.persist(new AuditLog(tenantId, userId, action, entity, entityId, ip, userAgent,
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
