package lk.wms.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void formatAndUniqueness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = Ulid.next();
            assertTrue(id.matches("[0-9A-HJKMNP-TV-Z]{26}"), id);
            assertTrue(seen.add(id), "duplicate " + id);
        }
    }

    @Test
    void timePrefixSortsByTime() {
        SecureRandom r = new SecureRandom();
        assertEquals("0000000000", Ulid.encode(0, r).substring(0, 10));
        assertEquals("7ZZZZZZZZZ", Ulid.encode((1L << 48) - 1, r).substring(0, 10));
        String earlier = Ulid.encode(1_700_000_000_000L, r);
        String later = Ulid.encode(1_700_000_000_001L, r);
        assertTrue(earlier.substring(0, 10).compareTo(later.substring(0, 10)) < 0);
    }
}
