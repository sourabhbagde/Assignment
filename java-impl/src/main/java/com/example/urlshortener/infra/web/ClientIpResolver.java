package com.example.urlshortener.infra.web;

import com.example.urlshortener.application.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the client IP for rate-limiting and analytics.
 *
 * <p>{@code X-Forwarded-For} is honoured <b>only</b> when {@code app.trust-
 * forwarded-for=true} — i.e. the deployment is known to sit behind a proxy that
 * overwrites the header. Otherwise a client could spoof the header to evade rate
 * limits or poison analytics, so we use the socket peer address.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(AppProperties props) {
        this.trustForwardedFor = props.trustForwardedFor();
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",", 2)[0].strip();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
