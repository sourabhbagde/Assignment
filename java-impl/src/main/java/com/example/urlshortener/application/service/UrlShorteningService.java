package com.example.urlshortener.application.service;

import com.example.urlshortener.application.config.AppProperties;
import com.example.urlshortener.application.port.in.ManageLinksUseCase;
import com.example.urlshortener.application.port.in.ResolveUrlUseCase;
import com.example.urlshortener.application.port.in.ShortenUrlUseCase;
import com.example.urlshortener.application.port.out.ResolutionCache;
import com.example.urlshortener.application.port.out.ShortCodeGenerator;
import com.example.urlshortener.application.port.out.ShortLinkRepository;
import com.example.urlshortener.application.port.out.ClickEventRepository;
import com.example.urlshortener.application.port.out.UrlSafetyInspector;
import com.example.urlshortener.domain.ShortCode;
import com.example.urlshortener.domain.exception.AliasAlreadyExistsException;
import com.example.urlshortener.domain.exception.CodeAllocationException;
import com.example.urlshortener.domain.exception.LinkGoneException;
import com.example.urlshortener.domain.exception.LinkNotFoundException;
import com.example.urlshortener.domain.exception.RequestValidationException;
import com.example.urlshortener.domain.model.LinkStatistics;
import com.example.urlshortener.domain.model.ShortLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Use-case interactor for links. Pure orchestration over output ports — no SQL,
 * no HTTP, no framework beyond the {@code @Service}/{@code @Transactional}
 * markers. All business rules for create / resolve / manage live here.
 */
@Service
public class UrlShorteningService implements ShortenUrlUseCase, ResolveUrlUseCase, ManageLinksUseCase {

    private static final Logger log = LoggerFactory.getLogger(UrlShorteningService.class);

    private final ShortLinkRepository links;
    private final ClickEventRepository clicks;
    private final ResolutionCache cache;
    private final ShortCodeGenerator generator;
    private final UrlSafetyInspector inspector;
    private final AppProperties props;
    private final Clock clock;

    public UrlShorteningService(ShortLinkRepository links,
                                ClickEventRepository clicks,
                                ResolutionCache cache,
                                ShortCodeGenerator generator,
                                UrlSafetyInspector inspector,
                                AppProperties props,
                                Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.cache = cache;
        this.generator = generator;
        this.inspector = inspector;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    @Override
    @Transactional
    public Result shorten(Command command) {
        UrlSafetyInspector.Result safe = inspector.inspect(command.longUrl());
        Instant now = clock.instant();
        Instant expiresAt = command.optionalExpiresAt().orElse(null);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new RequestValidationException("expiresAt must be in the future");
        }

        // Custom alias: explicit intent, never deduped, uniqueness enforced up front
        // and again by the DB constraint.
        if (command.optionalAlias().isPresent()) {
            ShortCode alias = ShortCode.ofAlias(command.optionalAlias().get());
            if (links.existsByCode(alias.value())) {
                throw new AliasAlreadyExistsException(alias.value());
            }
            ShortLink stored = insert(alias.value(), safe, now, expiresAt, command);
            return new Result(stored, false);
        }

        // Dedupe: return the existing active link for an identical destination.
        if (command.dedupe()) {
            var reusable = links.findReusable(safe.normalizedHash(), now);
            if (reusable.isPresent()) {
                log.debug("reusing existing code {} for identical destination", reusable.get().code());
                return new Result(reusable.get(), true);
            }
        }

        // Generate a random code, retrying on the (astronomically rare) collision.
        for (int attempt = 1; attempt <= props.codeMaxAttempts(); attempt++) {
            String candidate = ShortCode.ofGenerated(generator.newCode()).value();
            if (links.existsByCode(candidate)) {
                continue;
            }
            try {
                return new Result(insert(candidate, safe, now, expiresAt, command), false);
            } catch (ShortLinkRepository.CodeConflictException race) {
                if (attempt == props.codeMaxAttempts()) {
                    break;
                }
                log.debug("code {} lost an insert race, retrying (attempt {})", candidate, attempt);
            }
        }
        throw new CodeAllocationException();
    }

    private ShortLink insert(String code, UrlSafetyInspector.Result safe, Instant now,
                             Instant expiresAt, Command command) {
        ShortLink link = ShortLink.newLink(
                code, safe.normalizedUrl(), safe.normalizedHash(),
                now, expiresAt, command.createdBy(), command.metadata());
        return links.insert(link);
    }

    // ----------------------------------------------------------------- resolve

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(String code) {
        Instant now = clock.instant();

        var cached = cache.get(code);
        if (cached.isPresent()) {
            ResolutionCache.Entry e = cached.get();
            if (e.expiresAt() != null && !e.expiresAt().isAfter(now)) {
                cache.evict(code);
                throw LinkGoneException.expired(code);
            }
            return new Resolution(e.linkId(), e.code(), e.longUrl());
        }

        ShortLink link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        if (!link.active()) {
            throw LinkGoneException.deactivated(code);
        }
        if (link.isExpiredAt(now)) {
            throw LinkGoneException.expired(code);
        }

        cache.put(code, new ResolutionCache.Entry(
                link.id(), link.code(), link.longUrl(), link.expiresAt().orElse(null)));
        return new Resolution(link.id(), link.code(), link.longUrl());
    }

    // ------------------------------------------------------------------ manage

    @Override
    @Transactional(readOnly = true)
    public ShortLink get(String code) {
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(offset, 0);
        List<ShortLink> items = links.list(safeLimit, safeOffset);
        return new Page(items, links.count(), safeLimit, safeOffset);
    }

    @Override
    @Transactional
    public void deactivate(String code) {
        // 404 only if it never existed; deactivating an already-inactive link is a no-op success.
        links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        links.deactivateByCode(code);
        cache.evict(code);
    }

    @Override
    @Transactional(readOnly = true)
    public LinkStatistics statistics(String code, Instant from, Instant to, int topN) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException("`from` must be before `to`");
        }
        ShortLink link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        long id = link.id();

        String fromDay = from == null ? null : from.toString().substring(0, 10);
        String toDay = to == null ? null : to.toString().substring(0, 10);

        return new LinkStatistics(
                link.code(),
                link.longUrl(),
                link.createdAt(),
                clicks.totalClicks(id),
                clicks.clicksBetween(id, from, to),
                from,
                to,
                clicks.dailyCounts(id, fromDay, toDay),
                clicks.topReferrers(id, from, to, topN),
                clicks.topUserAgents(id, from, to, topN),
                clicks.lastClickAt(id));
    }
}
