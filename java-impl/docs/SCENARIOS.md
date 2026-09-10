# Three Scenarios

Each shows: requirement understanding → decomposition → execution → validation.
These are the actual pieces of work that produced this codebase.

---

## 1. Greenfield — "Build the URL shortener with core APIs, analytics and reliability"

### Requirement understanding
High-level ask, well-defined in outline. Normalised into a concrete problem:
a read-heavy redirect service where **redirect availability and latency must not
depend on the write path or the analytics consumer**, with the security posture
of a service that accepts arbitrary user URLs.

### Decomposition (with sequencing / dependencies)

| # | Task | Depends on | Acceptance |
| --- | --- | --- | --- |
| G1 | Domain model + typed errors (`ShortLink`, `ClickEvent`, `LinkStatistics`, `DomainException` tree) | — | invariants enforced at construction; no framework imports |
| G2 | Ports: `ShortLinkRepository`, `ClickEventRepository`, `ResolutionCache`, `ShortCodeGenerator`, `UrlSafetyInspector`, `ClickRecorder` | G1 | interfaces only; no impl leakage |
| G3 | Schema + JDBC adapters (raw stream + `click_daily` rollup) | G2 | idempotent DDL; all SQL parameterised; cascade delete |
| G4 | `SecureRandom` base62 generator + `ShortCode` value object (alias rules, reserved words) | G1 | unit tests: length/alphabet/collision/reserved/traversal |
| G5 | `SsrfAwareUrlSafetyInspector` (scheme, private-IP incl. obfuscated, creds, control chars) + `UrlNormalizer` (dedupe hash) | G1 | 35-case unit matrix passes |
| G6 | `UrlShorteningService` use-case interactor (create/dedupe/alias/collision-retry, resolve, deactivate, stats) | G2–G5 | service unit tests with mocked ports |
| G7 | `BufferedClickRecorder` — bounded queue, batched flush, backpressure, `@PreDestroy` flush | G2,G3 | click spike doesn't block redirect; shutdown flushes |
| G8 | `InProcessLruResolutionCache` (LRU + TTL) | G2 | eviction + expiry unit tests |
| G9 | Web layer: controllers, DTOs, `GlobalExceptionHandler` (one error envelope) | G6 | every failure path → typed `{error:{...}}` |
| G10 | Infra filters: request-id, rate-limit (split write/redirect buckets), security headers, body-size | G9 | `429`+`Retry-After`; headers present; oversized → `413` |
| G11 | Config (`AppProperties` validated), health/readiness, graceful shutdown, startup sanity checks | all | bad config aborts boot; prod rejects placeholder salt |
| G12 | Tests (unit + web IT) + docs | all | `./mvnw test` green |

### Execution
Built inner-to-outer so each layer was tested before the next depended on it.
AI drafted repository boilerplate, the SSRF test matrix, DTOs and first-draft
docs; engineer designed the async analytics semantics, the dedupe-by-hash
approach, the IP-range arithmetic, and every decision in ARCHITECTURE.md.

### Validation
103 tests (unit + MockMvc integration on in-memory H2). End-to-end `curl` smoke
covering create / dedupe / alias / SSRF (incl. `http://2130706433/`) / redirect /
410 / stats / rate-limit / health. `./mvnw -Pquality verify` for SpotBugs.

---

## 2. Brownfield — "Redirect latency spikes under load; the redirect does a synchronous analytics write on the hot path"

### Requirement understanding
Bug/refactor on an existing flow. Symptom: p99 redirect latency tracks database
write latency; a click spike or a slow disk stalls redirects. Root cause: the
redirect handler writes the click event **inline** before responding.

### Codebase reasoning — impacted modules / data flow

```
BEFORE:  GET /{code} ──▶ resolve(code) ──▶ INSERT click_events (await) ──▶ 302
                                   \__ every redirect blocks on a DB round-trip;
                                       write contention == redirect contention
```

Impacted: `RedirectController` (hot path), the click-write SQL, the redirect
integration tests, and the stats read path (needs to stay correct while writes
become asynchronous and batched).

### Decomposition

| # | Task | Acceptance |
| --- | --- | --- |
| B1 | Introduce `ClickRecorder` port with contract "`record()` returns immediately, never blocks, never throws" | interface + Javadoc |
| B2 | `BufferedClickRecorder`: bounded `ArrayBlockingQueue`, single flusher thread, flush every 1s **or** at `batchSize` | re-entrancy-guarded; unit-level behaviour |
| B3 | One transaction per batch: batch `INSERT click_events` + upsert `click_daily` rollup | stats numbers unchanged vs synchronous version |
| B4 | Backpressure: queue full → drop + counter (`analytics.dropped`), surfaced on `/health` and readiness | never grows unbounded; readiness flips to `OUT_OF_SERVICE` at 90% |
| B5 | Failure handling: transient DB error → re-queue batch, retry next tick | no silent loss under transient failure |
| B6 | `@PreDestroy` drains the queue before the pool closes | graceful-shutdown test |
| B7 | `RedirectController` calls `record(...)` wrapped so analytics can never break a redirect; add `Cache-Control: no-store` | redirect works even if recorder throws |

```
AFTER:   GET /{code} ──▶ resolve (cache-first) ──▶ queue.offer(event) ──▶ 302     (no awaited I/O)
                                                        │
                                   click-flusher thread ─┴─▶ batched txn: click_events + click_daily
```

### Execution
The port seam (B1) let the controller change and the implementation change land
independently. First AI draft `await`ed the DB inside `record()` — rejected;
re-specified "synchronous, I/O-free". Second draft had a re-entrancy bug
(concurrent flush double-draining) — engineer added the `draining` CAS guard and
the re-queue-on-error path.

### Validation
`RedirectControllerIT` (redirect still 302 with recorder exercised),
`AnalyticsIT` (3 hits → `flush()` → stats show 3; deactivated link records
nothing; IP stored only as a 64-hex hash), plus the `ClickRecorder.Snapshot`
counters asserted via `/health`. Trade-off explicitly accepted and documented:
**at-most-once** analytics on hard crash, in exchange for redirect availability.

---

## 3. Ambiguous — "Add analytics to the URL shortener"

### Requirement understanding — the ambiguities
"Analytics" is under-specified. Identified questions and the resolutions taken
(assumptions, all documented and cheap to revisit):

| Ambiguity | Options | Decision & why |
| --- | --- | --- |
| What is a "click"? | every hit vs unique visitors vs dedup within N min | **Every successful redirect.** Unique-ish signal kept via `ip_hash` for later, without committing to a sessionisation model now. |
| Which dimensions? | time, referrer, UA, geo, device | **time + referrer + user-agent.** Geo/device need a GeoIP/UA-parse dependency and carry more privacy weight — deferred, not precluded. |
| Real-time or batch? | live counter vs periodic rollup | **Near-real-time:** rollup written within the flush interval (~1s). Good enough for a dashboard; avoids a streaming stack. |
| Retention? | forever vs windowed | **Raw events kept; rollup kept.** A retention/archival job is a noted extension (raw stream is the thing that grows). |
| PII? | store IP / UA / referrer raw | **IP only as salted SHA-256**, never raw, never logged. Referrer/UA truncated. Prod refuses the placeholder salt. |
| Query shape? | totals only vs time series vs breakdowns | totals + `from/to` window + daily series + top-N referrers/UAs. |

### Normalised engineering problem
Add `GET /api/v1/urls/{code}/stats` returning `{ totalClicks, windowClicks,
window, daily[], topReferrers[], topUserAgents[], lastClickedAt }`, backed by an
append-only `click_events` stream and a `click_daily` rollup, fed by the
non-blocking recorder from scenario 2, with IP hashed at ingestion.

### Decomposition
A1 `ClickEvent` model + `ip_hash` contract · A2 `click_events` + `click_daily`
schema · A3 `ClickEventRepository` (batch write + rollup upsert; aggregate reads)
· A4 `IpHasher` (salted SHA-256) wired into `RedirectController` · A5
`LinkStatistics` domain type + `UrlShorteningService.statistics(code, from, to,
topN)` with window-ordering validation · A6 `LinkStatisticsResponse` + controller
endpoint, ISO date **or** timestamp parsing, inclusive end-of-day for bare `to` ·
A7 tests.

### Validation
`AnalyticsIT`: aggregation correctness (3 hits, 2 referrers, 2 UAs),
window filter (`from` in the future → `windowClicks=0` while `totalClicks`
ignores the window), inverted window → `400 VALIDATION_ERROR`, deactivated links
record nothing, and a direct DB assertion that `ip_hash` matches `[0-9a-f]{64}`
and contains no recoverable address. Assumptions above are restated in
[ENGINEERING_SUMMARY.md](ENGINEERING_SUMMARY.md#assumptions).
