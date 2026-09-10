package com.example.urlshortener.adapter.out.codec;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    private static String norm(String raw) {
        URI uri = URI.create(raw);
        return UrlNormalizer.normalize(uri, uri.getHost().toLowerCase());
    }

    @Test
    void dropsDefaultPortFragmentAndSortsQuery() {
        assertThat(norm("https://Example.com:443/p?b=2&a=1#section"))
                .isEqualTo("https://example.com/p?a=1&b=2");
        assertThat(norm("http://example.com:80/"))
                .isEqualTo("http://example.com/");
    }

    @Test
    void addsRootPathWhenAbsent() {
        assertThat(norm("https://example.com")).isEqualTo("https://example.com/");
    }

    @Test
    void keepsNonDefaultPortAndRepeatedParams() {
        assertThat(norm("https://example.com:8443/a?x=2&x=1"))
                .isEqualTo("https://example.com:8443/a?x=1&x=2");
    }

    @Test
    void semanticallyEqualUrlsNormaliseIdentically() {
        assertThat(norm("https://HOST.example/Path?z=1&a=2"))
                .isEqualTo(norm("https://host.example:443/Path?a=2&z=1"));
    }
}
