package com.example.urlshortener.application.port.out;

/**
 * Output port for destination-URL safety. Given a raw URL string it either
 * returns a normalized, safe-to-store form or throws
 * {@link com.example.urlshortener.domain.exception.InvalidUrlException}.
 *
 * <p>"Safe" here means: absolute http/https, within the size budget, not
 * pointing at a loopback / private / link-local / reserved address or an
 * internal hostname (SSRF), and free of credential/control-character abuse.
 */
public interface UrlSafetyInspector {

    Result inspect(String rawUrl);

    /**
     * @param normalizedUrl  canonical form of the destination (what we store & redirect to)
     * @param normalizedHash SHA-256 hex of {@code normalizedUrl}, used for dedupe lookups
     */
    record Result(String normalizedUrl, String normalizedHash) {
    }
}
