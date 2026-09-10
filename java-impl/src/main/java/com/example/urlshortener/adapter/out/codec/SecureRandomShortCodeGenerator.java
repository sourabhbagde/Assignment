package com.example.urlshortener.adapter.out.codec;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.ShortCodeGenerator;
import com.example.urlshortener.domain.ShortCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Random base62 codes from {@link SecureRandom}.
 *
 * <p>Random (not counter-encoded) so that: nothing leaks about how many links
 * exist or their creation order; no cross-instance coordination is needed to
 * scale writes; codes aren't enumerable. Collisions are handled by the caller's
 * bounded retry against the unique index — at length 7 (62^7 ≈ 3.5e12) the
 * collision probability with 1e7 stored links is ~3e-6.
 */
@Component
public class SecureRandomShortCodeGenerator implements ShortCodeGenerator {

    private static final char[] ALPHABET = ShortCode.ALPHABET.toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public SecureRandomShortCodeGenerator(AppProperties props) {
        this.length = props.codeLength();
    }

    @Override
    public String newCode() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
