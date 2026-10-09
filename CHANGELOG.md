# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 1.0.0-SNAPSHOT

### Added
- **Distribution**: Built OS-matrix deployment pipeline generating custom JREs via jlink and Windows installers via jpackage, pivoting away from GraalVM Native Image due to jdk.attach dynamic loading constraints.
- **Core Diagnostics**: Live JVM discovery via JDK Attach API (`JvmAttachService`).
- **Memory & Heap**: Inspection of heap regions, GC metrics, and allocation pressure (`MemoryMXBeanClient`).
- **Threads**: Thread dump extraction and cyclic deadlock detection (`ThreadMXBeanClient`).
- **Spring Beans**: ApplicationContext bean hierarchy inspection with JMX fallback (`SpringBeansClient`).
- **Database Pools**: Live metric extraction for HikariCP connection pools (`HikariMXBeanClient`).
- **PostgreSQL Inspection**: Pure JDBC schema metadata reader, sequential scan bottleneck detection, and slow query profiling via `pg_stat_statements` (`PostgresSchemaReader`).
- **Remote Actuator**: Lightweight generic HTTP client for Spring Boot Actuator endpoints (`/health`, `/metrics`, `/startup`) with `jvm-mcp actuator` CLI and MCP integration.
- **Security**: Basic and Bearer auth support for Actuator endpoints, plus TLS `--insecure` override.
- **Server**: Stdio JSON-RPC Model Context Protocol (MCP) server implementation (`ServeCommand`).
- **Infrastructure**: Multi-OS GitHub Actions CI matrix with automated JaCoCo coverage summaries.

### Changed
- **Testing**: Elevated test coverage across all `core` and `server` modules to 100% on critical paths.
- **Architecture**: Enforced Deep Modules and SOLID Port interfaces (`MemoryDiagnosticPort`, `ThreadDiagnosticPort`, `HikariDiagnosticPort`).
- **Documentation**: Standardized repository language to English and streamlined README formatting.

### Fixed
- **Testing Frameworks**: Fixed GitHub Actions IPv6 vs IPv4 loopback resolution causing random ConnectExceptions on Ubuntu/macOS by binding MockWebServer to 0.0.0.0.
- **Code Coverage**: Adjusted Pitest mutation thresholds and Jacoco boundaries to account for missing Docker daemon on Windows GitHub runners.
- **PostgreSQL Heuristics Testing**: Patched missing index heuristic testing to correctly execute >100 sequential scans to overcome Postgres internal test thresholds.
- **Resource Management**: Balanced `try-with-resources` blocks in CLI commands to prevent connection leaks.
- **Attach Lifecycle**: Fixed nullable `VirtualMachine` references within the `AttachResult.success` factory.
- **Artifact Names**: Handled `-jar` display names edge cases during main class extraction.

### Security
- **SSRF Protection**: Enforced strict host injection natively inside Java 21 HttpClient for Actuator queries, preventing dynamic URL header injection.
- **OOM Defenses**: Implemented a 30MB HttpResponse limiting handler to prevent Billion Laughs and payload Tarpits from exhausting the JVM-MCP agent memory.
- Added Gitleaks automated secret scanning in the CI pipeline.
- Explicit credential warnings when submitting Actuator credentials over plain HTTP.

