package com.example.urlshortener.application.service;

import com.example.urlshortener.application.port.in.ResolveUrlUseCase;
import com.example.urlshortener.application.port.in.ShortenUrlUseCase;
import com.example.urlshortener.application.port.out.ClickEventRepository;
import com.example.urlshortener.application.port.out.ResolutionCache;
import com.example.urlshortener.application.port.out.ShortCodeGenerator;
import com.example.urlshortener.application.port.out.ShortLinkRepository;
import com.example.urlshortener.application.port.out.UrlSafetyInspector;
import com.example.urlshortener.domain.exception.AliasAlreadyExistsException;
import com.example.urlshortener.domain.exception.CodeAllocationException;
import com.example.urlshortener.domain.exception.LinkGoneException;
import com.example.urlshortener.domain.exception.LinkNotFoundException;
import com.example.urlshortener.domain.exception.RequestValidationException;
import com.example.urlshortener.domain.model.ShortLink;
import com.example.urlshortener.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlShorteningServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final ShortLinkRepository links = mock(ShortLinkRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);
    private final ResolutionCache cache = mock(ResolutionCache.class);
    private final ShortCodeGenerator generator = mock(ShortCodeGenerator.class);
    private final UrlSafetyInspector inspector = mock(UrlSafetyInspector.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private UrlShorteningService service;

    @BeforeEach
    void setUp() {
        service = new UrlShorteningService(links, clicks, cache, generator, inspector, TestFixtures.props(), clock);
        when(inspector.inspect(any())).thenReturn(new UrlSafetyInspector.Result("https://example.com/", "hash-abc"));
        when(links.insert(any())).thenAnswer(inv -> {
            ShortLink l = inv.getArgument(0);
            return ShortLink.rehydrate(42L, l.code(), l.longUrl(), l.normalizedHash(),
                    l.createdAt(), l.expiresAt().orElse(null), l.active(), null, l.metadata());
        });
    }

    private ShortenUrlUseCase.Command cmd(String url, String alias, Instant expiresAt, boolean dedupe) {
        return new ShortenUrlUseCase.Command(url, alias, expiresAt, dedupe, null, Map.of());
    }

    // ---------------------------------------------------------------- create

    @Test
    void generatesCodeAndPersistsWhenNoDedupeHit() {
        when(generator.newCode()).thenReturn("abc1234");
        when(links.existsByCode("abc1234")).thenReturn(false);
        when(links.findReusable(eq("hash-abc"), any())).thenReturn(Optional.empty());

        ShortenUrlUseCase.Result result = service.shorten(cmd("https://example.com", null, null, true));

        assertThat(result.reused()).isFalse();
        assertThat(result.link().code()).isEqualTo("abc1234");
        verify(links).insert(any());
    }

    @Test
    void reusesExistingLinkOnDedupeHit() {
        ShortLink existing = ShortLink.rehydrate(7L, "exist12", "https://example.com/", "hash-abc",
                NOW.minusSeconds(60), null, true, null, Map.of());
        when(links.findReusable(eq("hash-abc"), any())).thenReturn(Optional.of(existing));

        ShortenUrlUseCase.Result result = service.shorten(cmd("https://example.com", null, null, true));

        assertThat(result.reused()).isTrue();
        assertThat(result.link().code()).isEqualTo("exist12");
        verify(links, never()).insert(any());
    }

    @Test
    void customAliasBypassesDedupeAnd409sWhenTaken() {
        when(links.existsByCode("promo")).thenReturn(true);
        assertThatThrownBy(() -> service.shorten(cmd("https://example.com", "promo", null, true)))
                .isInstanceOf(AliasAlreadyExistsException.class);
        verify(links, never()).findReusable(any(), any());
    }

    @Test
    void rejectsExpiryInThePast() {
        assertThatThrownBy(() ->
                service.shorten(cmd("https://example.com", null, NOW.minusSeconds(1), true)))
                .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void retriesOnCodeCollisionThenSucceeds() {
        when(generator.newCode()).thenReturn("dupdup1", "freshx2");
        when(links.existsByCode("dupdup1")).thenReturn(true);   // collides on the pre-check
        when(links.existsByCode("freshx2")).thenReturn(false);

        ShortenUrlUseCase.Result result = service.shorten(cmd("https://example.com", null, null, false));

        assertThat(result.link().code()).isEqualTo("freshx2");
    }

    @Test
    void throwsWhenCodeSpaceCannotBeAllocated() {
        when(generator.newCode()).thenReturn("takenxx");
        when(links.existsByCode("takenxx")).thenReturn(true); // every attempt collides

        assertThatThrownBy(() -> service.shorten(cmd("https://example.com", null, null, false)))
                .isInstanceOf(CodeAllocationException.class);
    }

    // --------------------------------------------------------------- resolve

    @Test
    void resolveThrowsNotFoundForUnknownCode() {
        when(cache.get("nope")).thenReturn(Optional.empty());
        when(links.findByCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("nope")).isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void resolveThrowsGoneForDeactivatedLink() {
        when(cache.get("dead12")).thenReturn(Optional.empty());
        when(links.findByCode("dead12")).thenReturn(Optional.of(ShortLink.rehydrate(
                1L, "dead12", "https://example.com/", "h", NOW.minusSeconds(10), null, false, null, Map.of())));
        assertThatThrownBy(() -> service.resolve("dead12")).isInstanceOf(LinkGoneException.class);
    }

    @Test
    void resolveThrowsGoneForExpiredLinkAndEvictsCache() {
        when(cache.get("old123")).thenReturn(Optional.empty());
        when(links.findByCode("old123")).thenReturn(Optional.of(ShortLink.rehydrate(
                1L, "old123", "https://example.com/", "h",
                NOW.minusSeconds(100), NOW.minusSeconds(1), true, null, Map.of())));
        assertThatThrownBy(() -> service.resolve("old123")).isInstanceOf(LinkGoneException.class);
    }

    @Test
    void resolveServesFromCacheWithoutTouchingRepository() {
        when(cache.get("hot123")).thenReturn(Optional.of(
                new ResolutionCache.Entry(9L, "hot123", "https://example.com/hot", null)));

        ResolveUrlUseCase.Resolution r = service.resolve("hot123");

        assertThat(r.longUrl()).isEqualTo("https://example.com/hot");
        verify(links, never()).findByCode(any());
    }

    // ---------------------------------------------------------------- manage

    @Test
    void deactivateEvictsCacheAnd404sWhenAbsent() {
        when(links.findByCode("gone12")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate("gone12")).isInstanceOf(LinkNotFoundException.class);

        when(links.findByCode("live12")).thenReturn(Optional.of(ShortLink.rehydrate(
                1L, "live12", "https://example.com/", "h", NOW, null, true, null, Map.of())));
        service.deactivate("live12");
        verify(links).deactivateByCode("live12");
        verify(cache).evict("live12");
    }

    @Test
    void statisticsRejectsInvertedWindow() {
        when(links.findByCode("win123")).thenReturn(Optional.of(ShortLink.rehydrate(
                1L, "win123", "https://example.com/", "h", NOW, null, true, null, Map.of())));
        assertThatThrownBy(() ->
                service.statistics("win123", NOW, NOW.minusSeconds(10), 10))
                .isInstanceOf(RequestValidationException.class);
    }
}
