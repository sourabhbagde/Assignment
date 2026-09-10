package com.example.urlshortener.adapter.out.codec;

import com.example.urlshortener.domain.ShortCode;

/** Stateless base62 helpers over the shared {@link ShortCode#ALPHABET}. */
public final class Base62 {

    private static final char[] ALPHABET = ShortCode.ALPHABET.toCharArray();
    private static final int BASE = ALPHABET.length;

    private Base62() {
    }

    /** Encode an unsigned long. Used only where a numeric encoding is wanted; codes are random. */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        if (value == 0) {
            return String.valueOf(ALPHABET[0]);
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(ALPHABET[(int) (v % BASE)]);
            v /= BASE;
        }
        return sb.reverse().toString();
    }
}
