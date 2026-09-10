# Final Engineering Summary

## Plan & rationale

Build a URL shortener as a **Clean Architecture** Spring Boot service where the
redirect path is fast and available independent of the write path and the
analytics consumer, and where accepting arbitrary user URLs is treated as a
security boundary. Layers (`domain` → `application` → `adapter`/`infra`) with the
dependency rule pointing inward, so every I/O concern (DB, cache, rate limiter,
analytics sink, URL-safety) sits behind a port and is swappable without touching
business rules. Delivered inner-to-outer, each layer tested before the next
depends on it.

## Artifacts

| Artifact | Location |
| --- | --- |
| Runnable service (`./mvnw spring-boot:run` / `java -jar`) | `src/main/...`, `pom.xml`, `mvnw` |
| Domain model + typed error hierarchy | `domain/` |
| Use-case interactors (create/resolve/manage) + buffered analytics writer | `application/service/` |
| Ports (6 output, 3 input) | `application/port/` |
| JDBC adapters + portable schema | `adapter/out/persistence/`, `resources/schema.sql` |
| SSRF-aware URL inspector + normalizer + base62 generator | `adapter/out/security/`, `adapter/out/codec/` |
| In-process LRU+TTL cache | `adapter/out/cache/` |
| REST controllers, DTOs, single error envelope | `adapter/in/web/` |
| Filters: request-id, split rate-limit, security headers, body-size; IP hashing | `infra/web/` |
| Health/readiness contributor, startup sanity checks | `infra/health/`, `infra/` |
| 103 tests (unit + MockMvc integration) | `src/test/...` |
| Architecture overview, three scenarios, this summary | `docs/` |

## AI-assisted execution (traceability)

| Disposition | Examples |
| --- | --- |
| **Generated & accepted** (reviewed, minor edits) | JDBC repositories, DTOs, controllers wiring, health indicator, most tests, error hierarchy, `LruCache` |
| **Generated & materially edited** | `AppProperties` validation; `SsrfAwareUrlSafetyInspector` IP logic (AI used string-prefix checks — replaced with integer/mask arithmetic + inet_aton-style parser for obfuscated forms; added IPv4-mapped IPv6); `BufferedClickRecorder` (AI `await`ed in `record()` and had a re-entrancy bug — added sync contract + `draining` CAS guard + re-queue-on-error); rate-limit `X-Forwarded-For` gated behind `trust-forwarded-for` |
| **Rejected, re-prompted** | `Math.random()` for codes → `SecureRandom`; blocking analytics write on hot path; silent config coercion; timer-dependent tests → deterministic `flush()` + `MutableClock` |
| **Engineer-authored, no AI** | task decomposition & acceptance criteria, `click_daily` rollup design, async/at-most-once analytics semantics, dedupe-by-normalized-hash, decisions D1–D9, all sign-offs |

**Quality gates** (all green): `./mvnw test` (103), `./mvnw -Pquality verify`
(SpotBugs), manual security review of the URL-inspector and input handling,
redirect hot-path reasoned for zero awaited analytics I/O.
**Secure AI usage:** no secrets/PII/internal hostnames in prompts; every
suggested dependency verified as real/maintained before use; security-sensitive
code got a second adversarial review pass (which caught the CGNAT and
IPv4-mapped-IPv6 gaps). **Human sign-off** on: schema, SSRF logic, analytics
concurrency, redirect-status default.

## Risks, trade-offs & validation

| Risk / trade-off | Decision | Validation / guardrail |
| --- | --- | --- |
| Analytics lost on hard crash | at-most-once (buffered queue) in exchange for redirect availability | bounded queue + drop counter on `/health`; readiness flips at 90%; `@PreDestroy` flush |
| Rate limiter is per-instance | acceptable for prototype; Redis is the documented swap | separate write/redirect buckets; `429`+`Retry-After` tested |
| H2 is single-writer, not the prod datastore | behind `ShortLinkRepository` port; portable DDL | repository contract tested; Postgres swap is one adapter |
| DNS-rebinding after create-time check | server never fetches the URL, only redirects the client; optional `validate-dns` | 35-case SSRF matrix incl. obfuscated IPs |
| Open-redirect is the product | mitigations: rate limit, soft-delete takedown, `createdBy` attribution hook, `Referrer-Policy: no-referrer` | — |
| Cache staleness on deactivate/expire | write paths call `cache.evict`; expiry re-checked on cache hit | `RedirectControllerIT` 410 cases |
| Weak IP-hash salt de-anonymises analytics | prod profile refuses the placeholder salt at startup | `StartupSanityChecks` |

## Assumptions

- Callers send RFC 3986 URLs (ASCII / percent-encoded); raw-Unicode hosts are rejected, not guessed.
- Codes are case-sensitive; `301` is opt-in because it defeats analytics and slows takedowns.
- Single deployment instance for the prototype (in-process cache & rate limiter); `trust-forwarded-for=false` unless explicitly behind a controlled proxy.
- "Click" = every successful redirect; near-real-time (~1s) rollup is sufficient; geo/device analytics and event retention policy are out of scope.
- H2 file DB under `./data/`; UTC everywhere.

## Limitations

- No auth/multi-tenancy — `created_by` is a plumbed-through hook, not enforced.
- No abuse/malware blocklist for destinations (design hook only).
- Rate limiting, cache and analytics buffer are per-process; horizontal scaling needs the Redis/queue swaps in ARCHITECTURE.md § Scaling path.
- No background job yet for purging expired links or archiving old `click_events`.
- SpotBugs is the only static-analysis gate; no coverage threshold enforced in CI.

## Testing approach

- **Unit** (`*Test`, plain JUnit): pure logic with adversarial inputs — SSRF/scheme matrix (35 cases incl. decimal/hex/octal/short/IPv4-mapped IP forms), alias rules & path-traversal, base62 length/alphabet/collision, LRU eviction & TTL (hand-advanced `MutableClock`), and the use-case interactor with mocked ports (dedupe, alias 409, collision-retry, resolve 404/410, window validation).
- **Integration** (`*IT`, `@SpringBootTest` + MockMvc, in-memory H2, full filter chain): every endpoint and every failure branch — create/dedupe/alias/conflict/validation/unknown-field/wrong-method/wrong-content-type, redirect + `410` on deactivate/expire, junk-path fast-404, analytics aggregation + windowing + `ip_hash`-at-rest, rate-limit `429`+`Retry-After` + bucket independence, health/readiness (no internals leaked), and the error-envelope + `X-Request-Id` contract.
- Deterministic: no `sleep`, no ports, no fixtures to clean; each IT class gets its own context + fresh DB via a distinct property. Run with `./mvnw test` (~15s). Static analysis: `./mvnw -Pquality verify`.
