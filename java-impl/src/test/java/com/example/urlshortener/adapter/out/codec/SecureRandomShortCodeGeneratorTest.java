package com.example.urlshortener.adapter.out.codec;

import com.example.urlshortener.domain.ShortCode;
import com.example.urlshortener.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRandomShortCodeGeneratorTest {

    private final SecureRandomShortCodeGenerator generator =
            new SecureRandomShortCodeGenerator(TestFixtures.props());

    @Test
    void generatesCodesOfConfiguredLengthFromAlphabet() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.newCode();
            assertThat(code).hasSize(7);
            assertThat(code.chars()).allMatch(c -> ShortCode.ALPHABET.indexOf(c) >= 0);
        }
    }

    @Test
    void isEffectivelyCollisionFreeOverManyDraws() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(generator.newCode());
        }
        assertThat(seen).hasSize(20_000);
    }
}
