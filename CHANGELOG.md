# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **MCP Server Modular Architecture**:
  - Deconstructed `ServeCommand.java` (from a 503-line god class down to 70 lines) into clean, single-responsibility modules:
    - `dev.jvmmcp.server.transport.McpTransport`: Manages non-blocking stdio loop, virtual threads per task executor, and synchronized response framing.
    - `dev.jvmmcp.server.protocol.McpDispatcher`: Implements JSON-RPC 2.0 protocol negotiation (`initialize`, `ping`, `tools/list`, `tools/call`) and structured error taxonomy.
    - `dev.jvmmcp.server.tool.ToolRegistry` & `ToolHandler`: Extensible plug-and-play tool registry and domain handler interfaces.
    - Domain tool handlers: `JvmDiagnosticToolHandler`, `SpringBeansToolHandler`, `PostgresToolHandler`, and `ActuatorToolHandler`.
    - `dev.jvmmcp.server.tool.ToolArgs`: Boundary-checked, type-safe argument parsing utilities.
- **Multi-Platform Readiness Diagnostics**:
  - Enhanced `jvm-mcp doctor` with JVM runtime vendor detection (HotSpot vs OpenJ9 / IBM Semeru), Docker container isolation checks (`/.dockerenv`, `/run/.containerenv`), and Linux `yama/ptrace_scope` verification.
  - Added dedicated unit tests for all diagnostic check methods in `DoctorCommandTest`.
- **Release Matrix Expansion & Automated Homebrew Tap Synchronization**:
  - Expanded GitHub Actions release build matrix to include macOS Intel (`macos-13`) and Linux ARM64 (`ubuntu-24.04-arm`), producing all 4 UNIX archives + Windows executable.
  - Added automated SHA-256 checksum injection in `release.yml` that synchronizes `homebrew/jvm-mcp.rb` from `checksums.txt` and publishes the updated formula as an atomic release asset.
- **Platform Compatibility Matrix**:
  - Documented explicit operating system, JDK vendor, container, and security permission compatibility in `README.md`.

### Changed
- **Harmonized Quality Gates & Test Suite**:
  - Raised `mcp-server` PIT mutation testing threshold from 25% to **70%** (achieving 73% kill rate across 347 mutations).
  - Raised `mcp-server` JaCoCo branch coverage threshold from 55% to **65%** (achieving 82% branch coverage and 93% instruction coverage).
  - Expanded `mcp-server` test suite from 80 to **165 tests** with 0 failures and 0 errors.
  - Managed `maven-jar-plugin` version (3.4.2) in root POM plugin management, eliminating Maven model warnings.
- **Accurate Project Positioning**:
  - Updated documentation to emphasize "Zero runtime framework overhead with self-contained packaging and zero target-application modifications" over generic "zero-dependency" claims.

### Fixed
- **Defensive JSON Parsing in `SimpleJson`**: Malformed numbers (`-`, `1.`, `1e`, `-1.5e-`) and truncated or non-hexadecimal `\u` escapes now fail with a positioned `IllegalArgumentException` instead of leaking `NumberFormatException`; non-ASCII digits are no longer routed into the number parser.

## [1.1.0] - 2026-10-11

### Added
- **Concurrency**: Dispatched incoming MCP JSON-RPC tool requests to Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to eliminate Head-of-Line blocking on ping heartbeats during long-running tool executions.
- **Thread Safety**: Added synchronized monitor locking (`synchronized (responseLock)`) for all outbound stdio JSON-RPC responses to prevent frame interleaving.
- **Diagnostics CLI**: Implemented `jvm-mcp doctor` subcommand (`DoctorCommand`) validating host OS readiness, Java 21 runtime version, and Attach API user matching / `ptrace_scope` permissions.
- **Dynamic Versioning**: Implemented runtime version resolution (`Package.getImplementationVersion()` with `/version.properties` and `1.1.0-SNAPSHOT` fallback) and Picocli `IVersionProvider` for `jvm-mcp --version`.
- **Release Pipeline**: Implemented two-stage collector pipeline (`build-matrix` + `publish-release`) in GitHub Actions generating SHA-256 checksums (`checksums.txt` and `.sha256`) and atomic GitHub Releases.
- **Homebrew Formula**: Upgraded `homebrew/jvm-mcp.rb` with standard multi-architecture blocks (`on_macos`, `on_linux`, `aarch64`, `amd64`) and `libexec` symlink layout.
- **Dependency Management**: Centralized test dependencies in root POM using `testcontainers-bom` to prevent fractured partial updates by automated tools.

### Changed
- **Build Architecture**: Formally retired obsolete `native` module from the Maven reactor following the architectural transition to `jlink`/`jpackage`.
- **MCP Server**: Captured unhandled runtime exceptions with standard JSON-RPC `-32603` (Internal Error) responses instead of unhandled thread terminations.
- **Logging Hygiene**: Bound SLF4J logging strictly to `System.err` via `simplelogger.properties` in `mcp-server` to keep `stdout` pure for JSON-RPC transport framing.
- **Distribution Scripts**: Hardened root `install.sh` with cryptographic SHA-256 pre-extraction verification, multi-architecture detection, and automatic cleanup trap.
- **Shade Plugin**: Upgraded `maven-shade-plugin` to `3.6.2`.

### Removed
- **Redundant Scripts**: Removed obsolete `scripts/install.sh` and duplicate `.github/homebrew/jvm-mcp.rb`.

### Fixed
- **CLI Exit Codes**: Ensured `BeansCommand` returns exit code `1` when Actuator endpoints are unreachable instead of silently exiting with code `0`.
- **CLI Usability**: Enabled `mixinStandardHelpOptions = true` across `DoctorCommand`, `PgCommand`, and `ActuatorCommand` so `--help` exits cleanly with code `0`.
- **Lifecycle Leaks**: Added guaranteed `try-finally` detachment for `VirtualMachine` instances if JMX connection initialization throws an exception in `ServeCommand`.
- **Testing Hermeticity**: Replaced external Docker Testcontainers in `HikariMXBeanClientTest` with in-memory H2 database instances for 100% hermetic execution across Windows, macOS, and Linux runners.

### Security
- **PostgreSQL JDBC SSRF & RCE Hardening**: Implemented canonical URI validation in `PostgresSchemaReader`:
  - Rejects `userinfo` evasion attempts and multi-host syntax.
  - Enforces loopback host resolution (`localhost`, `127.0.0.1`, `::1`).
  - Strips dangerous driver properties (`socketFactory`, `sslrootcert`, `sslkey`, `sslpassword`, `options=`).
  - Sanitizes SQL text in slow query reports to redact embedded passwords.
- **ReDoS Mitigation**: Hardened `GlobMatcher` by collapsing consecutive wildcards (`*+` -> `*`) and bounding maximum pattern length to 256 characters.
- **JSON Recursion Boundary**: Implemented `MAX_SERIALIZATION_DEPTH = 100` in `SimpleJson` to guard against `StackOverflowError` caused by cyclic object graphs.

---

## [1.0.1] - 2026-10-09

### Added
- Multi-OS GitHub Actions release matrix compiling stripped JREs via `jlink` (Linux/macOS) and `jpackage` app-images (Windows).
- Universal installation script `install.sh` for one-command installation via `curl | bash`.
- Homebrew formula specification for multi-architecture macOS and Linux installation.

### Fixed
- Fixed IPv6 vs IPv4 loopback resolution timeouts in MockWebServer during GitHub Actions CI runs.
- Guarded JMX client connections with `try-with-resources` to prevent file descriptor leaks.

---

## [1.0.0] - 2026-10-09

### Added
- Core Attach API discovery engine (`JvmAttachService`) detecting local JVM processes, PIDs, and frameworks (Spring Boot, Quarkus, Micronaut).
- Memory inspection engine (`MemoryMXBeanClient`) reporting heap/non-heap regions, GC pauses, and allocation pressure.
- Thread diagnostics engine (`ThreadMXBeanClient`) reporting thread states, full stack dumps, and detecting circular deadlocks.
- Spring Boot ApplicationContext inspection (`SpringBeansClient`) listing beans, autowired dependencies, and initialization scopes via dynamic JMX.
- HikariCP connection pool diagnostics (`HikariMXBeanClient`) analyzing pool saturation, active connections, and clock leap heuristics.
- PostgreSQL schema inspector (`PostgresSchemaReader`) reporting table structures, row estimates, missing indexes, and slow queries.
- Remote Actuator client (`ActuatorClient`) querying `/health`, `/metrics`, and `/startup` endpoints with Basic/Bearer auth support.
- In-house JSON-RPC 2.0 stdio server (`ServeCommand`) implementing the Model Context Protocol (MCP).
- Terminal CLI interface powered by Picocli (`list`, `memory`, `threads`, `beans`, `pg`, `actuator`, `serve`).
