package com.example.urlshortener.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Baseline hardening headers on every response (this service pulls in no Spring
 * Security, so nothing else sets them).
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — no MIME sniffing;</li>
 *   <li>{@code X-Frame-Options: DENY} + {@code frame-ancestors 'none'} — no framing;</li>
 *   <li>{@code Referrer-Policy: no-referrer} — our short-URL path (which can be a
 *       secret) is never forwarded to the destination;</li>
 *   <li>a locked-down {@code Content-Security-Policy} — the API serves only JSON,
 *       so nothing should ever execute;</li>
 *   <li>{@code Cache-Control: no-store} on API responses so link data / analytics
 *       are not cached by intermediaries (the redirect controller sets its own).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");

        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
