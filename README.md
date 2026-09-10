# AI-Assisted Software Engineering Assignment — URL Shortener

The deliverable is a production-grade URL shortener service with core APIs,
analytics and reliability features, built with disciplined AI-assisted execution.

**➡ Implementation and all documentation: [`java-impl/`](java-impl/)**

| Deliverable | Where |
| --- | --- |
| Working prototype (runnable end-to-end) | [`java-impl/`](java-impl/) — `./mvnw spring-boot:run` |
| Setup instructions + API reference + edge-case/security matrix | [`java-impl/README.md`](java-impl/README.md) |
| Architecture overview (components, control flow, key decisions, scaling) | [`java-impl/docs/ARCHITECTURE.md`](java-impl/docs/ARCHITECTURE.md) |
| Three scenarios — greenfield, brownfield, ambiguous | [`java-impl/docs/SCENARIOS.md`](java-impl/docs/SCENARIOS.md) |
| Plan/rationale, AI traceability, risks/trade-offs, assumptions, limitations, testing approach | [`java-impl/docs/ENGINEERING_SUMMARY.md`](java-impl/docs/ENGINEERING_SUMMARY.md) |

**Stack:** Java 17, Spring Boot 3, Spring JDBC + embedded H2 (zero setup), JUnit 5.
**Tests:** `cd java-impl && ./mvnw test` — 103 unit + integration tests.
