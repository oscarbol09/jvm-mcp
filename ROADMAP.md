# Project Roadmap & Contribution Scope

> Real-time JVM diagnostic tools for AI coding assistants via Model Context Protocol (MCP).

This roadmap outlines current development milestones, architectural priorities, and high-impact areas open for community contributions.

---

## Current Status: Phase 6 (Completed)

- [x] **Phase 0: Architecture Scaffolding**
  - Maven multi-module architecture (`core`, `agent`, `mcp-server`, `native`).
  - Dual-layer design (Pure Java SDK for `< 15ms` CLI startup vs optional Spring AI SSE transport).
  - GitHub Actions CI matrix on Temurin JDK 21.

- [x] **Phase 1: JVM Discovery & Attach Lifecycle**
  - JDK Attach API wrapper (`JvmAttachService`) for live process discovery.
  - Framework heuristics (`FrameworkDetector` for Spring Boot, Quarkus, Micronaut, Plain Java).
  - Lazy diagnostic agent extractor with SHA-256 caching in `~/.cache/jvm-mcp/`.
  - Tabular CLI commands (`jvm-mcp list`, `ps`, `ls`).

---

- [x] **Phase 2: Memory, Heap & Concurrency Diagnostics**
  - Live heap region breakdown (Eden, Survivor, Old Gen) & allocation pressure evaluation (`MemoryMXBeanClient`).
  - GC pause tracker and diagnostic recommendations (`get_heap_summary`, `detect_memory_pressure`).
  - Heap class histogram via `jcmd GC.class_histogram` Attach API stream parsing (`HeapHistogramReader`).
  - Structured thread dumps and deadlock cycle reconstruction (`ThreadMXBeanClient`).
  - CLI diagnostic commands (`jvm-mcp memory <pid>`, `jvm-mcp threads <pid>`).

---

- [x] **Phase 3: Spring Beans Inspector**
  - ApplicationContext bean hierarchy inspection (`SpringBeansClient`).
  - Dual-strategy discovery: Actuator HTTP discovery (`/actuator/beans`) with automatic JMX fallback (`org.springframework.boot:type=Endpoint,name=Beans`).
  - Case-insensitive glob filtering (`*Service*`, `*Repository*`, `*Controller*`).
  - Direct dependency graph and scope inspection (`jvm-mcp beans <pid> [--filter] [--detail]`).
  - Zero-dependency JSON parser engine (`SimpleJson`).

---

- [x] **Phase 4: Database Connection Pool Diagnostics (HikariCP)**
  - Query `com.zaxxer.hikari:type=Pool (*)` MBeans across single and multi-datasource architectures.
  - Live metric extraction: active, idle, pending connections, max pool size, acquire latency.
  - Saturation ratio calculation and automated leak/exhaustion diagnostics.

---

- [x] **Phase 5: PostgreSQL Schema & Stat Inspector**
  - Pure JDBC metadata reader (`PostgresSchemaReader`) for tables, indexes, and foreign keys.
  - Query `pg_stat_user_tables` to identify sequential scan bottlenecks and missing indexes.
  - Query `pg_stat_statements` for slow query execution profiling.
  - Dedicated CLI (`jvm-mcp pg`) and MCP endpoints (`inspect_pg_schema`, `find_missing_indexes`, `find_slow_queries`).

---

- [x] **Phase 6: Remote Spring Boot Actuator Client**
  - Lightweight `java.net.http.HttpClient` client (`ActuatorClient`) for `/actuator/health`, `/actuator/metrics`, and `/actuator/startup`.
  - Basic Auth and Bearer token header propagation (`ActuatorAuth`).
  - Dedicated CLI (`jvm-mcp actuator`) and MCP endpoints (`get_actuator_health`, `get_actuator_metrics`, `get_actuator_startup`).

---

## Active Milestone: Phase 7 & 8 — Packaging & Distribution (In Progress)

- [ ] GraalVM Native Image tracing agent automation on Linux/macOS.
- [ ] `jpackage` bundling for zero-dependency Windows `.exe`.
- [ ] Homebrew Formula (`brew install oscarbol09/tap/jvm-mcp`).

## Upcoming Community Milestones (Open for Contributions)

*(All current feature phases are active or completed. New proposals welcome!)*

---

## How to Claim an Area

1. Check existing issues or open a new one with the title `[Proposal] Milestone Name`.
2. Follow the architectural constraints in [`CONTRIBUTING.md`](CONTRIBUTING.md).
3. Open a draft PR early for architectural alignment.
