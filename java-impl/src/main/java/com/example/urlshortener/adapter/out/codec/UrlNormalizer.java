package com.example.urlshortener.adapter.out.codec;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonicalises a URL so that destinations that are semantically identical hash
 * the same (enables create-time deduplication). Deliberately conservative — it
 * only touches parts that are unambiguously equivalent:
 * <ul>
 *   <li>scheme &amp; host lower-cased; host IDNA/punycode-encoded to ASCII;</li>
 *   <li>default ports (80/443) dropped;</li>
 *   <li>fragment removed (never sent to the server);</li>
 *   <li>empty path normalised to "/";</li>
 *   <li>query parameters sorted by name (value order within a name preserved).</li>
 * </ul>
 * Percent-encoding and path contents are left untouched.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /**
     * @param uri  the parsed, already-validated destination
     * @param host the lower-cased, de-bracketed host the caller resolved (may differ
     *             from {@link URI#getHost()} for registry-based authorities)
     */
    public static String normalize(URI uri, String host) {
        String scheme = uri.getScheme().toLowerCase();
        String asciiHost = toAsciiHost(host);

        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        String query = sortQuery(uri.getRawQuery());

        StringBuilder sb = new StringBuilder(scheme).append("://");
        if (host.indexOf(':') >= 0) {
            sb.append('[').append(asciiHost).append(']'); // IPv6 literal
        } else {
            sb.append(asciiHost);
        }
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    private static String toAsciiHost(String host) {
        if (host.indexOf(':') >= 0) {
            return host; // IPv6 literal — leave as-is
        }
        try {
            return IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            return host; // already-ASCII or unconvertible; host was syntax-checked upstream
        }
    }

    private static String sortQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> pairs = new ArrayList<>(List.of(rawQuery.split("&", -1)));
        pairs.sort(Comparator.comparing(UrlNormalizer::keyOf).thenComparing(p -> p));
        return String.join("&", pairs);
    }

    private static String keyOf(String pair) {
        int eq = pair.indexOf('=');
        return eq >= 0 ? pair.substring(0, eq) : pair;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
