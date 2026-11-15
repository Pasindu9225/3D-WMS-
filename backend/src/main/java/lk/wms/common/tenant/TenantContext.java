package lk.wms.common.tenant;

import java.util.function.Supplier;

/**
 * SEC-24: the tenant of the current request. Set by the auth filter from the JWT "tid" claim (task 4).
 * Hibernate reads it through {@link TenantResolver} when a session opens, so set it BEFORE the
 * transaction starts. With no tenant set, tenant queries match nothing and inserts fail (fail closed).
 */
public final class TenantContext {

    /** Matches no row: tenant IDs start at 1 and are unsigned. */
    static final Long NONE = -1L;
    /** Hibernate "root" tenant: sees every tenant. Only for lookups that happen before the tenant is known. */
    static final Long SYSTEM = 0L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static <T> T callAs(long tenantId, Supplier<T> work) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("invalid tenant id");
        }
        return run(tenantId, work);
    }

    /** Cross-tenant access, e.g. finding a user by email at login. Keep these calls rare and reviewed. */
    public static <T> T callAsSystem(Supplier<T> work) {
        return run(SYSTEM, work);
    }

    /** The current company, for the rare native query that must set tenant_id itself; fails outside one. */
    public static long require() {
        Long id = CURRENT.get();
        if (id == null || id <= 0) {
            throw new IllegalStateException("no tenant in context");
        }
        return id;
    }

    static Long current() {
        Long id = CURRENT.get();
        return id != null ? id : NONE;
    }

    /** For TenantFilter, which wraps a servlet chain that throws checked exceptions. */
    static Long swap(Long tenantId) {
        Long previous = CURRENT.get();
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
        return previous;
    }

    private static <T> T run(Long tenantId, Supplier<T> work) {
        Long previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
