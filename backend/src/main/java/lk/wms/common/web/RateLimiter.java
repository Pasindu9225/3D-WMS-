package lk.wms.common.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SEC-60: token buckets per IP or per user, kept in memory (Caffeine) instead of Redis.
 * ponytail: per-JVM counts; with several app servers each one counts on its own.
 */
@Component
public class RateLimiter {

    /** UPLOAD: SEC-45, file uploads per user. */
    /** SCAN: phone scans (receiving, putaway, transfers, barcode lookups), which come faster than office clicks. */
    /** EXPORT: Excel/PDF exports per user; one can be 200,000 rows (security review v1.0 F1). */
    public enum Scope { GLOBAL, LOGIN, USER, UPLOAD, SCAN, EXPORT }

    private final Map<Scope, Integer> perMinute = new EnumMap<>(Scope.class);
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    public RateLimiter(@Value("${wms.rate-limit.global-per-minute}") int global,
            @Value("${wms.rate-limit.login-per-minute}") int login,
            @Value("${wms.rate-limit.user-per-minute}") int user,
            @Value("${wms.rate-limit.upload-per-minute:20}") int upload,
            @Value("${wms.rate-limit.scan-per-minute:300}") int scan,
            @Value("${wms.rate-limit.export-per-minute:10}") int export) {
        perMinute.put(Scope.EXPORT, export);
        perMinute.put(Scope.SCAN, scan);
        perMinute.put(Scope.GLOBAL, global);
        perMinute.put(Scope.LOGIN, login);
        perMinute.put(Scope.USER, user);
        perMinute.put(Scope.UPLOAD, upload);
    }

    /** @return 0 if allowed, otherwise the seconds to wait (for the Retry-After header). */
    public long tryConsume(Scope scope, String key) {
        int limit = perMinute.get(scope);
        Bucket bucket = buckets.get(scope + ":" + key, k -> Bucket.builder()
                .addLimit(l -> l.capacity(limit).refillGreedy(limit, Duration.ofMinutes(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? 0 : TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
    }
}
