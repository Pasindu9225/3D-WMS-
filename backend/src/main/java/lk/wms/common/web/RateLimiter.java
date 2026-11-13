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

    public enum Scope { GLOBAL, LOGIN, USER }

    private final Map<Scope, Integer> perMinute = new EnumMap<>(Scope.class);
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build();

    public RateLimiter(@Value("${wms.rate-limit.global-per-minute}") int global,
            @Value("${wms.rate-limit.login-per-minute}") int login,
            @Value("${wms.rate-limit.user-per-minute}") int user) {
        perMinute.put(Scope.GLOBAL, global);
        perMinute.put(Scope.LOGIN, login);
        perMinute.put(Scope.USER, user);
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
