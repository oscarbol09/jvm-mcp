# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 1.0.0-SNAPSHOT

### Added
- **Core Diagnostics**: Live JVM discovery via JDK Attach API (`JvmAttachService`).
- **Memory & Heap**: Inspection of heap regions, GC metrics, and allocation pressure (`MemoryMXBeanClient`).
- **Threads**: Thread dump extraction and cyclic deadlock detection (`ThreadMXBeanClient`).
- **Spring Beans**: ApplicationContext bean hierarchy inspection with JMX fallback (`SpringBeansClient`).
- **Database Pools**: Live metric extraction for HikariCP connection pools (`HikariMXBeanClient`).
- **PostgreSQL Inspection**: Pure JDBC schema metadata reader, sequential scan bottleneck detection, and slow query profiling via `pg_stat_statements` (`PostgresSchemaReader`).
- **Security**: Basic and Bearer auth support for Actuator endpoints, plus TLS `--insecure` override.
- **Server**: Stdio JSON-RPC Model Context Protocol (MCP) server implementation (`ServeCommand`).
- **Infrastructure**: Multi-OS GitHub Actions CI matrix with automated JaCoCo coverage summaries.

### Changed
- **Testing**: Elevated test coverage across all `core` and `server` modules to 100% on critical paths.
- **Architecture**: Enforced Deep Modules and SOLID Port interfaces (`MemoryDiagnosticPort`, `ThreadDiagnosticPort`, `HikariDiagnosticPort`).
- **Documentation**: Standardized repository language to English and streamlined README formatting.

### Fixed
- **Resource Management**: Balanced `try-with-resources` blocks in CLI commands to prevent connection leaks.
- **Attach Lifecycle**: Fixed nullable `VirtualMachine` references within the `AttachResult.success` factory.
- **Artifact Names**: Handled `-jar` display names edge cases during main class extraction.

### Security
- Added Gitleaks automated secret scanning in the CI pipeline.
- Explicit credential warnings when submitting Actuator credentials over plain HTTP.
