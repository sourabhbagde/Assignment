package com.example.urlshortener.domain;

import com.example.urlshortener.domain.exception.InvalidAliasException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeTest {

    @Test
    void acceptsWellFormedAlias() {
        assertThat(ShortCode.ofAlias("my-Link_1").value()).isEqualTo("my-Link_1");
        assertThat(ShortCode.ofAlias("  spaced  ".replace(" ", "")).value()).isEqualTo("spaced");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ab", "with space", "slash/es", "dot.dot", "..", "%2e%2e",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 67 chars
    })
    void rejectsMalformedAlias(String alias) {
        assertThatThrownBy(() -> ShortCode.ofAlias(alias)).isInstanceOf(InvalidAliasException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "health", "admin", "actuator", "robots.txt", "favicon.ico"})
    void rejectsReservedAlias(String alias) {
        assertThat(ShortCode.RESERVED).contains(alias);
        assertThatThrownBy(() -> ShortCode.ofAlias(alias)).isInstanceOf(InvalidAliasException.class);
    }

    @Test
    void looksLikeCodeIsAFastStructuralGuard() {
        assertThat(ShortCode.looksLikeCode("abc123")).isTrue();
        assertThat(ShortCode.looksLikeCode("my-alias_9")).isTrue();
        assertThat(ShortCode.looksLikeCode("favicon.ico")).isFalse();
        assertThat(ShortCode.looksLikeCode("a")).isFalse();
        assertThat(ShortCode.looksLikeCode("api")).isFalse();
        assertThat(ShortCode.looksLikeCode("../etc")).isFalse();
    }
}
