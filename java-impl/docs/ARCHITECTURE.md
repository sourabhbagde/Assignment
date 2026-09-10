# Architecture Overview

## Components & tools

| Component | Choice | Why |
| --- | --- | --- |
| HTTP / DI | Spring Boot 3 (Web MVC, embedded Tomcat) | Standard, reviewable, batteries-included (validation, actuator, graceful shutdown) |
| Persistence | Spring JDBC (`NamedParameterJdbcTemplate`) + **H2** (file mode) | Explicit SQL (no ORM magic), real transactions, zero-setup for a prototype; DDL is portable so Postgres is a mechanical swap |
| Validation | Jakarta Bean Validation + `@ConfigurationProperties(@Validated)` | One mechanism for request bodies and config; bad config aborts startup |
| Tests | JUnit 5, MockMvc, Mockito, AssertJ | In-process, fast, no ports |
| Build | Maven + wrapper (`./mvnw`); SpotBugs profile (`-Pquality`) | No local Maven needed to review |

## Layering (Clean Architecture)

```
        adapter/in/web ──▶ application/port/in ──▶ application/service ──▶ application/port/out ◀── adapter/out/*
             (controllers)      (use-case ifaces)      (interactors)          (boundaries)         (jdbc, cache, ssrf, codec)
                                                            │
                                                       domain/*  (entities, value objects, errors — zero framework imports)
```

The dependency rule points inward. `domain` knows nothing. `application` knows
`domain` only. `adapter`/`infra` know `application`. Wiring is Spring component
scan; the use-case interactor (`UrlShorteningService`) depends only on `port/out`
interfaces, so every I/O concern is swappable without touching business rules.

## Control flow

**Create — `POST /api/v1/urls`**
```
RateLimitFilter(write bucket) → BodySizeLimitFilter → bean validation
 → UrlController: resolve expiry (expiresAt XOR ttlSeconds), metadata byte budget
   → UrlShorteningService.shorten
      → UrlSafetyInspector.inspect   (scheme + SSRF + control-chars + creds → normalized URL + sha256)
      → custom alias?  ShortCode.ofAlias → existsByCode → 409 | INSERT
      → dedupe (default)? findReusable(hash) → return existing (200)
      → else: SecureRandom code → existsByCode → INSERT ; on UNIQUE race retry (≤6) ; exhausted → 503
   → 201 / 200  { code, shortUrl, longUrl, expiresAt, ... }
```

**Redirect — `GET /{code}` (hot path)**
```
RateLimitFilter(redirect bucket)
 → RedirectController: ShortCode.looksLikeCode? no → 404 (no DB)
   → ResolveUrlUseCase.resolve
       cache hit → check not-expired → return          (no DB)
       miss → findByCode → 404 | inactive→410 | expired→410(+evict) | cache.put
   → ClickRecorder.record(event)   ── enqueue only, no I/O, never throws ──
   → 302 Location: <longUrl>,  Cache-Control: no-store
```

**Analytics pipeline**
```
record() → bounded in-memory queue
         → single "click-flusher" thread: every 1s OR at batchSize
            → one transaction: batch INSERT click_events + upsert click_daily rollup
         → queue full → drop + count (shed analytics, not traffic)
         → @PreDestroy → final flush
```

## Data model

```
short_links(id, code UNIQUE, long_url, normalized_hash, created_at, expires_at,
            active, created_by, metadata)
    idx (normalized_hash, active)   -- dedupe
    idx (expires_at)                -- expiry sweeps
click_events(id, link_id→short_links ON DELETE CASCADE, code, occurred_at,
             referrer, user_agent, ip_hash)         -- raw, append-only
    idx (link_id, occurred_at)
click_daily(link_id, click_day, clicks, PK(link_id, click_day))   -- rollup
```

`click_daily` is written in the same transaction as the raw insert, so
"total clicks" and "clicks over time" never scan the growing raw stream.

## Key decisions

| # | Decision | Rationale | Trade-off accepted |
| --- | --- | --- | --- |
| D1 | Random base62 codes, not counter-encoded | No enumeration, no creation-order leak, no cross-node coordination | Needs a uniqueness check + bounded retry |
| D2 | Dedupe by normalized-URL hash, default on | Fewer rows, stable links, cache-friendly | Opt-out flag needed for callers who want distinct codes |
| D3 | Analytics async + batched, at-most-once | Redirect latency independent of DB; click spikes can't stall redirects | Small window of lost clicks on hard crash |
| D4 | `302` default redirect | `301` is aggressively cached → analytics blind, takedowns slow | Configurable via `app.redirect-status` |
| D5 | Soft delete, codes never recycled | Preserves history; never silently repoints a live link | Table grows; needs archival long-term |
| D6 | SSRF block on IP **literals** (incl. obfuscated) + optional DNS check | Covers the common attack cheaply without a mandatory network call on create | DNS-rebinding residual risk — mitigated because the server never fetches the URL |
| D7 | Client IP → salted SHA-256 at ingestion | Abuse/uniqueness signal without PII at rest | Raw IP unrecoverable; salt is rotatable |
| D8 | H2 behind a repository port | Runs with zero setup; real transactions | Not the production datastore — swap is one new adapter |
| D9 | Config validated at boot; prod refuses placeholder salt | Fail fast and loud | none meaningful |

## Failure modes

| Failure | Mitigation |
| --- | --- |
| Viral link / click spike | Async batched analytics; redirect does no analytics I/O |
| Analytics queue saturates | Bounded queue + drop-and-count; `/health/readiness` → `OUT_OF_SERVICE` so a LB drains the node |
| DB slow / briefly down | `busy`/connection timeouts; redirect reads from cache; failed batch re-queued and retried |
| Short-code collision | Random + UNIQUE + bounded retry; never overwrites |
| SSRF / internal probing | Scheme allowlist + private-IP-literal block + optional DNS re-check |
| Crash with queued events | At-most-once accepted; durable queue (Kafka/SQS) is the upgrade |
| Bad config in prod | Startup aborts |

## Scaling path (each step is one adapter, not a rewrite)

| Concern | Now | Next |
| --- | --- | --- |
| Storage | H2 file | `JdbcShortLinkRepository` → Postgres + read replicas (DDL already portable) |
| Redirect cache | in-process LRU | Redis behind the same `ResolutionCache` port (+ negative caching for 404s) |
| Rate limiting | in-process token bucket (per-node → N×) | same algorithm in Redis behind the same filter |
| Analytics | in-memory queue → H2 batch | `ClickRecorder.record` → Kafka/Kinesis producer; flush logic moves to a consumer → OLAP store |
| Deploy | single container | stateless app tier behind an LB; probes already split for it |
