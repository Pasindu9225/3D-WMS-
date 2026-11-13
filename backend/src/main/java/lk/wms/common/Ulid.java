package lk.wms.common;

import java.security.SecureRandom;

/**
 * ULID: 26 chars, Crockford base32, sortable by creation time.
 * Used for public IDs, session IDs and correlation IDs so internal numeric IDs are never exposed.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    // ponytail: not monotonic inside the same millisecond; add a counter if strict ordering is ever needed
    public static String next() {
        return encode(System.currentTimeMillis(), RANDOM);
    }

    static String encode(long timeMillis, SecureRandom random) {
        byte[] rnd = new byte[10];
        random.nextBytes(rnd);
        char[] out = new char[26];
        long time = timeMillis;
        for (int i = 9; i >= 0; i--) {          // 48-bit time -> 10 chars
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        fill(out, 10, rnd, 0);                  // 80 random bits -> 2 x 40 bits -> 16 chars
        fill(out, 18, rnd, 5);
        return new String(out);
    }

    private static void fill(char[] out, int at, byte[] rnd, int from) {
        long bits = 0;
        for (int i = from; i < from + 5; i++) {
            bits = (bits << 8) | (rnd[i] & 0xFF);
        }
        for (int i = at + 7; i >= at; i--) {
            out[i] = ALPHABET[(int) (bits & 31)];
            bits >>>= 5;
        }
    }
}
