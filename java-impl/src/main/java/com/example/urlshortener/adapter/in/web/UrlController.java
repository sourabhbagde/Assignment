package com.example.urlshortener.adapter.in.web;

import com.example.urlshortener.adapter.in.web.dto.CreateShortLinkRequest;
import com.example.urlshortener.adapter.in.web.dto.LinkStatisticsResponse;
import com.example.urlshortener.adapter.in.web.dto.PageResponse;
import com.example.urlshortener.adapter.in.web.dto.ShortLinkResponse;
import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.in.ManageLinksUseCase;
import com.example.urlshortener.application.port.in.ShortenUrlUseCase;
import com.example.urlshortener.domain.exception.RequestValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * CRUD + analytics for short links. Thin: it validates/parses the HTTP shape,
 * delegates to the use-case ports, and maps domain objects to DTOs. All business
 * rules live in {@code UrlShorteningService}.
 */
@RestController
@RequestMapping(path = "/api/v1/urls")
@Validated
public class UrlController {

    /** Structural guard on the path variable so junk never reaches the service. */
    private static final String CODE_REGEX = "^[0-9A-Za-z_-]{3,64}$";

    private final ShortenUrlUseCase shortenUrl;
    private final ManageLinksUseCase manageLinks;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public UrlController(ShortenUrlUseCase shortenUrl, ManageLinksUseCase manageLinks,
                        AppProperties props, ObjectMapper objectMapper) {
        this.shortenUrl = shortenUrl;
        this.manageLinks = manageLinks;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request) {
        if (request.expiresAt() != null && request.ttlSeconds() != null) {
            throw new RequestValidationException("provide either expiresAt or ttlSeconds, not both");
        }
        Instant expiresAt = resolveExpiry(request);
        enforceMetadataBudget(request);

        var command = new ShortenUrlUseCase.Command(
                request.url(),
                request.customAlias(),
                expiresAt,
                request.dedupeOrDefault(props.dedupeByDefault()),
                null,
                request.metadata());

        ShortenUrlUseCase.Result result = shortenUrl.shorten(command);
        ShortLinkResponse body = ShortLinkResponse.from(result.link(), props.baseUrl());
        HttpStatus status = result.reused() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).header(HttpHeaders.LOCATION, body.shortUrl()).body(body);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<ShortLinkResponse> list(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        ManageLinksUseCase.Page page = manageLinks.list(limit, offset);
        List<ShortLinkResponse> items = page.items().stream()
                .map(l -> ShortLinkResponse.from(l, props.baseUrl())).toList();
        return new PageResponse<>(items, page.total(), page.limit(), page.offset());
    }

    @GetMapping(path = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ShortLinkResponse get(@PathVariable @Pattern(regexp = CODE_REGEX) String code) {
        return ShortLinkResponse.from(manageLinks.get(code), props.baseUrl());
    }

    @GetMapping(path = "/{code}/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkStatisticsResponse stats(
            @PathVariable @Pattern(regexp = CODE_REGEX) String code,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant = parseBoundary(from, "from", false);
        Instant toInstant = parseBoundary(to, "to", true);
        return LinkStatisticsResponse.from(manageLinks.statistics(code, fromInstant, toInstant, 10));
    }

    @DeleteMapping(path = "/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable @Pattern(regexp = CODE_REGEX) String code) {
        manageLinks.deactivate(code);
    }

    // --------------------------------------------------------------- helpers

    private Instant resolveExpiry(CreateShortLinkRequest request) {
        if (request.ttlSeconds() != null) {
            return Instant.now().plusSeconds(request.ttlSeconds());
        }
        if (request.expiresAt() != null && !request.expiresAt().isBlank()) {
            return parseBoundary(request.expiresAt(), "expiresAt", false);
        }
        return null;
    }

    /**
     * Accepts a full ISO-8601 instant ({@code 2026-01-01T00:00:00Z}) or a bare
     * date ({@code 2026-01-01}). A bare date as an upper bound is treated as the
     * end of that day so an inclusive {@code to=2026-01-01} covers the whole day.
     */
    private Instant parseBoundary(String value, String field, boolean endOfDayIfDateOnly) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to date-only parsing
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return (endOfDayIfDateOnly ? date.atTime(23, 59, 59, 999_000_000) : date.atStartOfDay())
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new RequestValidationException("`" + field + "` must be an ISO-8601 date or timestamp");
        }
    }

    private void enforceMetadataBudget(CreateShortLinkRequest request) {
        if (request.metadata() == null || request.metadata().isEmpty()) {
            return;
        }
        try {
            int bytes = objectMapper.writeValueAsBytes(request.metadata()).length;
            if (bytes > props.metadataMaxBytes()) {
                throw new RequestValidationException(
                        "metadata exceeds " + props.metadataMaxBytes() + " bytes serialized");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RequestValidationException("metadata is not serialisable to JSON");
        }
    }
}
