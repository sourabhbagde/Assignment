package com.example.urlshortener.adapter.out.security;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.out.UrlSafetyInspector;
import com.example.urlshortener.domain.exception.InvalidUrlException;
import com.example.urlshortener.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfAwareUrlSafetyInspectorTest {

    private final SsrfAwareUrlSafetyInspector inspector =
            new SsrfAwareUrlSafetyInspector(TestFixtures.props());

    @Test
    void acceptsOrdinaryHttpsUrl() {
        UrlSafetyInspector.Result r = inspector.inspect("https://example.com/path?a=1");
        assertThat(r.normalizedUrl()).isEqualTo("https://example.com/path?a=1");
        assertThat(r.normalizedHash()).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>1</script>",
            "file:///etc/passwd",
            "ftp://ftp.example.com/x",
            "mailto:a@b.com",
            "tel:+123",
            " ",
            "not-a-url",
            "http://",
            "https://user:pass@example.com/",
    })
    void rejectsBadSchemesSyntaxAndCredentials(String url) {
        assertThatThrownBy(() -> inspector.inspect(url)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://10.1.2.3/",
            "http://172.16.9.9/",
            "http://192.168.0.5/",
            "http://169.254.169.254/latest/meta-data/",   // cloud metadata
            "http://100.64.1.1/",                          // CGNAT
            "http://[::1]/",
            "http://0.0.0.0/",
            "http://localhost/",
            "http://localhost:8080/admin",
            "http://printer.local/",
            "http://service.internal/",
            "http://2130706433/",                         // decimal 127.0.0.1
            "http://0x7f.0x0.0x0.0x1/",                    // hex 127.0.0.1
            "http://0177.0.0.1/",                          // octal 127.0.0.1
            "http://127.1/",                              // short form 127.0.0.1
            "http://[::ffff:127.0.0.1]/",                  // IPv4-mapped IPv6 loopback
    })
    void rejectsSsrfTargets(String url) {
        assertThatThrownBy(() -> inspector.inspect(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void allowsPrivateLiteralWhenGuardDisabled() {
        AppProperties relaxed = new AppProperties(
                "http://localhost:3000", 7, 6, "302", true, 2048,
                false, false, "s", 4096, false,
                new AppProperties.RateLimit(20, 2, 100, 50),
                new AppProperties.Analytics(1000, 200, 10_000, 512, 512),
                new AppProperties.Cache(1000, 60_000));
        UrlSafetyInspector.Result r = new SsrfAwareUrlSafetyInspector(relaxed).inspect("http://10.0.0.1/x");
        assertThat(r.normalizedUrl()).isEqualTo("http://10.0.0.1/x");
    }

    @Test
    void rejectsSelfReferentialDestination() {
        assertThatThrownBy(() -> inspector.inspect("https://localhost:3000/abcdef"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsControlCharactersForHeaderInjection() {
        assertThatThrownBy(() -> inspector.inspect("https://example.com/\r\nSet-Cookie:x"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void enforcesLengthBudget() {
        String longUrl = "https://example.com/" + "x".repeat(3000);
        assertThatThrownBy(() -> inspector.inspect(longUrl)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void normalisationIsQueryOrderAndCaseInsensitive() {
        String a = inspector.inspect("https://Example.COM:443/p?b=2&a=1#frag").normalizedHash();
        String b = inspector.inspect("https://example.com/p?a=1&b=2").normalizedHash();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void preservesAlreadyPunycodedHostAndLowercasesIt() {
        UrlSafetyInspector.Result r = inspector.inspect("https://XN--MNCHEN-3YA.example/p");
        assertThat(r.normalizedUrl()).isEqualTo("https://xn--mnchen-3ya.example/p");
    }

    @Test
    void rejectsRawUnicodeHost() {
        // Per RFC 3986 the caller must send an ASCII / percent-encoded URL; raw
        // Unicode hosts are rejected rather than guessed at.
        assertThatThrownBy(() -> inspector.inspect("https://münchen.example/"))
                .isInstanceOf(InvalidUrlException.class);
    }
}
