package com.example.urlshortener.infra.web;

import com.example.urlshortener.adapter.in.web.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects oversized request bodies early (before Jackson allocates anything) via
 * the declared {@code Content-Length}. Belt-and-braces alongside the DTO's
 * field-level {@code @Size} limits and the metadata byte budget.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 16 * 1024;

    private final ObjectMapper objectMapper;

    public BodySizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            Object rid = request.getAttribute(RequestIdFilter.ATTRIBUTE);
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                    "PAYLOAD_TOO_LARGE",
                    "request body exceeds " + MAX_BODY_BYTES + " bytes",
                    rid == null ? null : rid.toString(),
                    request.getRequestURI(),
                    null));
            return;
        }
        chain.doFilter(request, response);
    }
}
