package com.example.urlshortener.adapter.in.web;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.in.ResolveUrlUseCase;
import com.example.urlshortener.application.port.out.ClickRecorder;
import com.example.urlshortener.domain.ShortCode;
import com.example.urlshortener.domain.exception.LinkNotFoundException;
import com.example.urlshortener.domain.model.ClickEvent;
import com.example.urlshortener.infra.web.ClientIpResolver;
import com.example.urlshortener.infra.web.IpHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The redirect hot path: {@code GET /{code}} → 301/302 to the destination.
 *
 * <p>Kept deliberately minimal — structural reject of non-code paths, one
 * cache-first resolve, a fire-and-forget click record, then the redirect. No
 * awaited I/O for analytics; {@code Cache-Control: no-store} so a 302 keeps
 * counting hits and a takedown propagates immediately.
 */
@RestController
public class RedirectController {

    private final ResolveUrlUseCase resolveUrl;
    private final ClickRecorder clickRecorder;
    private final ClientIpResolver clientIp;
    private final IpHasher ipHasher;
    private final AppProperties props;
    private final int referrerMax;
    private final int userAgentMax;

    public RedirectController(ResolveUrlUseCase resolveUrl, ClickRecorder clickRecorder,
                             ClientIpResolver clientIp, IpHasher ipHasher, AppProperties props) {
        this.resolveUrl = resolveUrl;
        this.clickRecorder = clickRecorder;
        this.clientIp = clientIp;
        this.ipHasher = ipHasher;
        this.props = props;
        this.referrerMax = props.analytics().referrerMaxLength();
        this.userAgentMax = props.analytics().userAgentMaxLength();
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        if (!ShortCode.looksLikeCode(code)) {
            // Junk path / scanner / reserved word — 404 without a DB lookup.
            throw new LinkNotFoundException(code);
        }

        ResolveUrlUseCase.Resolution resolution = resolveUrl.resolve(code);

        recordClickQuietly(resolution, request);

        return ResponseEntity.status(props.redirectStatusCode())
                .header(HttpHeaders.LOCATION, resolution.longUrl())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private void recordClickQuietly(ResolveUrlUseCase.Resolution resolution, HttpServletRequest request) {
        try {
            clickRecorder.record(new ClickEvent(
                    resolution.linkId(),
                    resolution.code(),
                    Instant.now(),
                    truncate(request.getHeader(HttpHeaders.REFERER), referrerMax),
                    truncate(request.getHeader(HttpHeaders.USER_AGENT), userAgentMax),
                    ipHasher.hash(clientIp.resolve(request))));
        } catch (RuntimeException ignored) {
            // Analytics must never break a redirect.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
