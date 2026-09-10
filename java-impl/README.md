# URL Shortener (Java / Spring Boot)

A production-grade URL shortener: create short links, redirect fast, and get
per-link click analytics — with the reliability and security controls a real
deployment needs. Built for the *AI-Assisted Software Engineering* assignment.

- **Architecture overview** → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **Three scenarios** (greenfield / brownfield / ambiguous) → [docs/SCENARIOS.md](docs/SCENARIOS.md)
- **Plan, AI usage, risks, assumptions, limitations, testing approach** → [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md)

---

## Running it & checking the output

Requires **JDK 17+** and nothing else — storage is embedded H2, no DB server, no Docker.

```bash
cd java-impl
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")
```

### 1. Run the test suite (fastest check — no server needed)

```bash
./mvnw test
```

Expected tail:

```
[INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per-class results and any failure stack traces are also written to `target/surefire-reports/`.

### 2. Start the service

```bash
./mvnw spring-boot:run
# or:  ./mvnw -DskipTests package && java -jar target/url-shortener.jar
```

Wait for `Started UrlShortenerApplication` in the console. It listens on **http://localhost:3000**;
logs (one request-id per line) print to that console.

### 3. Exercise the API and read the responses (second terminal)

```bash
B=localhost:3000

# health
curl -s $B/health                                   # {"status":"UP", ...}

# create -> 201 + JSON body
curl -s -XPOST $B/api/v1/urls -H 'content-type: application/json' \
  -d '{"url":"https://developer.mozilla.org/en-US/docs/Web/HTTP"}'
# -> {"code":"aB3xK9p","shortUrl":"http://localhost:3000/aB3xK9p", ...}

CODE=aB3xK9p                                         # paste the code from above

curl -sI $B/$CODE                                    # 302, Location: https://developer.mozilla.org/...
curl -s  $B/$CODE -o /dev/null ; curl -s $B/$CODE -o /dev/null   # a couple of hits for analytics
curl -s  $B/api/v1/urls/$CODE                        # link metadata
curl -s  $B/api/v1/urls/$CODE/stats                  # click analytics
curl -s  $B/api/v1/urls?limit=5                      # list, newest first
curl -s -XDELETE $B/api/v1/urls/$CODE -o /dev/null -w '%{http_code}\n'   # 204
```

Handy `curl` flags for inspecting output:

| Flag | Shows |
| --- | --- |
| `-w '\n%{http_code}\n'` | the HTTP status code |
| `-D -` | response headers (`X-Request-Id`, `X-RateLimit-*`, `Location`, security headers) |
| `-i` | headers + body together |
| `\| jq` | pretty-print the JSON body |

### 4. Edge-case spot checks

```bash
curl -s $B/nonexistent -w '\n%{http_code}\n'                                                       # 404 LINK_NOT_FOUND
curl -s -XPOST $B/api/v1/urls -H 'content-type: application/json' -d '{"url":"http://169.254.169.254/"}'  # 400 INVALID_URL (SSRF)
curl -s -XPOST $B/api/v1/urls -H 'content-type: application/json' -d '{"url":"http://2130706433/"}'       # 400 (obfuscated loopback)
curl -s -XPOST $B/api/v1/urls -H 'content-type: application/json' -d '{"url":"javascript:alert(1)"}'      # 400
curl -s -XPOST $B/api/v1/urls -H 'content-type: application/json' -d '{"url":"https://ok.com","x":1}'     # 400 MALFORMED_BODY (unknown field)
curl -s -XPOST $B/api/v1/urls -H 'content-type: text/plain' -d 'x' -w '\n%{http_code}\n'                  # 415
curl -s -XDELETE $B/api/v1/urls -w '\n%{http_code}\n'                                                     # 405
```

### 5. Stop / reset

`Ctrl-C` in the server terminal — it drains in-flight requests and flushes buffered analytics before exiting.
The H2 data file lives at `java-impl/data/`; delete that folder for a clean slate.

---

## API

| Method & path | Purpose | Success |
| --- | --- | --- |
| `POST /api/v1/urls` | Shorten a URL. Body: `url` (required), `customAlias`, `expiresAt` (ISO-8601) **or** `ttlSeconds`, `dedupe` (default true), `metadata` (object) | `201` new · `200` reused (dedupe) |
| `GET /api/v1/urls` | List links, newest first. `?limit=1..100&offset=0` | `200` `{items,total,limit,offset}` |
| `GET /api/v1/urls/{code}` | Link metadata | `200` |
| `GET /api/v1/urls/{code}/stats` | Analytics. `?from=&to=` (ISO date or timestamp) | `200` |
| `DELETE /api/v1/urls/{code}` | Deactivate (soft). Redirects then return `410` | `204` |
| `GET /{code}` | Redirect to destination + record a click | `302` (configurable) |
| `GET /health` · `/health/liveness` · `/health/readiness` | Probes (status only, no internals) | `200` / `503` |

**Error envelope** (every 4xx/5xx):

```json
{ "error": { "code": "LINK_NOT_FOUND", "message": "...", "requestId": "…", "timestamp": "…", "path": "/…" } }
```

`X-Request-Id` is generated per request (honours a well-formed inbound one) and echoed in the header and the body.

---

## Edge cases & guardrails covered

| Area | Handling |
| --- | --- |
| **Scheme abuse** | Only `http`/`https`. `javascript:`, `data:`, `file:`, `ftp:`, `mailto:` … → `400 INVALID_URL` |
| **SSRF** | Rejects loopback / private / link-local / CGNAT / multicast / reserved IP literals — **including obfuscated forms**: decimal (`http://2130706433`), hex (`0x7f.0.0.1`), octal (`0177.0.0.1`), short (`127.1`), IPv4-mapped IPv6 (`[::ffff:127.0.0.1]`). Also `localhost`, `*.local`, `*.internal`, cloud-metadata hostnames, and the shortener's own host. Optional DNS re-check (`app.validate-dns=true`) for rebinding. |
| **Header injection** | URLs with CR/LF/tab/control/whitespace → `400`. Redirect `Location` only ever carries a normalized, pre-validated URL. |
| **Embedded credentials** | `https://user:pass@host` → `400` (phishing / secret leak). |
| **Oversized input** | `Content-Length > 16 KiB` → `413`; `url > 2048` → `400`; `metadata` serialized `> 4 KiB` → `400`. |
| **Unknown / malformed body** | Unknown JSON fields → `400 MALFORMED_BODY`; bad JSON → `400`; wrong `Content-Type` → `415`; wrong method → `405`. |
| **Alias safety** | 3–64 chars `[A-Za-z0-9_-]` only (no `.` `/` `..` `%2e`); reserved words (`api`, `health`, …) → `400`; already taken → `409`. |
| **Expiry** | Past `expiresAt` → `400`; both `expiresAt` and `ttlSeconds` → `400`; `ttlSeconds` capped at 5 years; expired link → `410 LINK_EXPIRED` and cache eviction. |
| **Deactivation** | Soft delete; code never recycled; redirect → `410 LINK_DEACTIVATED`; deleting a missing code → `404`, an already-inactive one → `204`. |
| **Dedupe / idempotency** | Identical destinations (query-order-insensitive) reuse the code and return `200`; `dedupe:false` opts out; custom aliases always bypass. |
| **Code collisions** | Random `SecureRandom` base62; unique DB constraint; bounded retry; exhaustion → `503 CODE_ALLOCATION_FAILED` with `Retry-After`. |
| **Abuse / DoS** | Per-IP token-bucket rate limiting, **separate budgets** for writes vs redirects; `429` + `Retry-After` + `X-RateLimit-*`. Pagination hard-capped at 100. |
| **Sensitive data** | Client IPs stored only as a salted SHA-256 (never raw, never logged). Logs carry no headers or bodies. Prod refuses to start with the placeholder `ip-hash-salt`. `/health` and errors never leak stack traces, SQL, class names or config. |
| **Redirect hot path** | Cache-first resolve; click recording is fire-and-forget (never blocks or fails a redirect); `Cache-Control: no-store` so a 302 keeps counting and takedowns propagate. |
| **Reliability** | Buffered + batched analytics (bounded queue, drop-and-count backpressure, flush on shutdown); graceful shutdown; split liveness/readiness probes. |
| **SQL injection** | 100% parameterised (`NamedParameterJdbcTemplate`); the one interpolated token is a hard-coded column name. |
| **Security headers** | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, locked-down CSP on every response. |

---

## Configuration (`app.*`, validated at startup — bad values abort the boot)

| Key | Default | Notes |
| --- | --- | --- |
| `app.base-url` | `http://localhost:3000` | origin used in `shortUrl` |
| `app.code-length` | `7` | 62⁷ ≈ 3.5×10¹² keyspace |
| `app.redirect-status` | `302` | `301`/`302`/`307`/`308` |
| `app.dedupe-by-default` | `true` | |
| `app.max-url-length` | `2048` | |
| `app.block-private-addresses` | `true` | SSRF guard |
| `app.validate-dns` | `false` | resolve + re-check host IPs |
| `app.ip-hash-salt` | *(placeholder)* | **set `IP_HASH_SALT` in prod** |
| `app.rate-limit.*` | 20/2 write, 100/50 redirect | capacity / refill-per-second |
| `app.analytics.*` | 1000 ms / 200 / 10000 | flush interval / batch / queue cap |
| `app.cache.*` | 5000 / 60000 ms | max entries / TTL |

---

## Project layout (Clean Architecture — dependencies point inward)

```
domain/            entities + value objects + typed errors        (no framework)
application/
  port/in          use-case interfaces (Shorten, Resolve, ManageLinks)
  port/out         boundaries (repositories, cache, generator, url-safety, click-recorder)
  service/         use-case interactors + buffered analytics writer
  config/          AppProperties (validated)
adapter/
  in/web/          controllers, DTOs, GlobalExceptionHandler
  out/persistence/ JDBC repositories (all SQL here)
  out/cache/       in-process LRU cache
  out/security/    SSRF-aware URL inspector
  out/codec/       base62, short-code generator, URL normalizer
infra/web/         request-id / rate-limit / security-headers / body-size filters, IP hashing
infra/health/      analytics-queue health indicator
infra/             startup sanity checks
```

