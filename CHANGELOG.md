# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Spring bean pagination and summary** (#217): `list_spring_beans` accepts `limit` (default 200, max 2000), `offset` and `summary`. Paginated responses include `totalBeans`, `returnedBeans` and `truncated`; summary mode returns per-context bean counts and the top 10 packages. `get_bean_detail` passes `beanName` to the parser so only matching name/alias candidates are built.

### Fixed
- **HikariCP empty pool list diagnostics** (#215): `list_hikari_pools` now returns a structured object, `{"pools": [...], "note": ...}`, instead of a bare array. When no HikariCP MBeans are registered (the Spring Boot default), `pools` is empty and `note` explains how to enable them; when pools are available, `note` is `null` and the existing metrics are returned under `pools`.
- **Credential redaction in non-JDBC connection URIs** (#200): `SensitiveDataRedactor` only scrubbed `jdbc:` URLs, so credentials embedded in `mongodb://`, `amqp://`, `redis://`, `postgres://`, `https://` (e.g. `spring.data.mongodb.uri`, `DATABASE_URL`) were forwarded to the model unredacted, and a password containing `@` was only partially masked. Added a generic userinfo pattern that redacts any scheme and splits on the last `@` before the host, so `u:pa@ss@host` becomes `u:***@host`.
- **JSON Serialization of File Paths and Scalars** (#201): Serialized file paths (`java.nio.file.Path`, `File`, `URI`, temporal accessors, and scalars) as escaped JSON strings instead of recursively iterating their name elements.
- **Docker Image Startup Failure** (#171): Replaced the `eclipse-temurin:21-jre-alpine` runtime with a `jlink`-assembled JRE on `alpine:3.24`. The stock JRE lacks `jdk.attach`, so every command (including `--version`) failed at startup with `NoClassDefFoundError: com/sun/tools/attach/VirtualMachine`. The custom runtime also includes `jdk.crypto.ec` for HTTPS Actuator endpoints and `jdk.jfr` so the `DiagnosticCommand` MBean works for self-inspection, and the `linux/amd64` image shrinks from ~290MB to ~113MB.

## [1.1.1] - 2026-10-22

### Added
- **Windows PowerShell One-Line Installer (`install.ps1`)**:
  - Direct installation via `irm .../install.ps1 | iex` with cryptographic SHA-256 integrity verification.
  - Architecture enforcement (x86_64/AMD64).
  - Staged atomic deployment with rollback capability on failure.
  - Automatic `User PATH` registration.
  - Parameterized execution support: `-Version`, `-InstallDir`, `-Uninstall`, and `-Help`.
- **Windows & Unix Uninstallers (`uninstall.ps1`, `uninstall.sh`)**:
  - Dedicated uninstallation scripts providing clean binary removal and environment PATH cleanup.
- **Universal Installer Flags in `install.sh`**:
  - Added CLI flag parsing for `--version <tag>`, `--uninstall`, `--install-dir <dir>`, `--bin-dir <dir>`, and `--help`.
  - Added idempotent cleaning before unpacking to prevent orphaned files during in-place upgrades.
- **Official Scoop Package Manager Manifest**:
  - Published `bucket/jvm-mcp.json` in `oscarbol09/homebrew-tap` repository and template in `scoop/jvm-mcp.json`.
  - Automated Scoop manifest generation and publication as a release asset in `.github/workflows/release.yml`.
- **Multi-Architecture Container Image (GHCR)**:
  - Multi-stage `Dockerfile` based on hardened `eclipse-temurin:21-jre-alpine` and unprivileged execution (`UID:GID 10001:10001`).
  - GitHub Actions workflow `.github/workflows/docker.yml` building and publishing multi-arch images (`linux/amd64`, `linux/arm64`) to `ghcr.io/oscarbol09/jvm-mcp`.

### Fixed
- **Windows Console Subsystem in `jpackage`**:
  - Added `--win-console` flag in `.github/workflows/release.yml` so that `jvm-mcp.exe` properly binds `stdin`, `stdout`, and `stderr` in PowerShell, CMD, and GUI process runners (Claude Desktop, Cursor).
- **GitHub Actions macOS x86_64 Runner Migration**:
  - Upgraded retired `macos-13` runner to `macos-15-intel` in `release.yml`, resolving indefinite queue stalls for Intel macOS builds.
- **PowerShell Remote Parameter Execution**:
  - Documented parameterized script invocation via `& ([scriptblock]::Create((irm ...)))`.

## [1.1.0] - 2026-10-21

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
- **Concurrency & Thread Safety**:
  - Dispatched incoming MCP JSON-RPC tool requests to Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to eliminate Head-of-Line blocking on ping heartbeats during long-running tool executions.
  - Added synchronized monitor locking (`synchronized (responseLock)`) for all outbound stdio JSON-RPC responses to prevent frame interleaving.
- **Dynamic Versioning**:
  - Implemented runtime version resolution (`Package.getImplementationVersion()` with `/version.properties` and fallback) and Picocli `IVersionProvider` for `jvm-mcp --version`.
- **Homebrew Formula**:
  - Upgraded `homebrew/jvm-mcp.rb` with standard multi-architecture blocks (`on_macos`, `on_linux`, `aarch64`, `amd64`) and `libexec` symlink layout.

### Changed
- **Harmonized Quality Gates & Test Suite**:
  - Raised `mcp-server` PIT mutation testing threshold from 25% to **70%** (achieving 73% kill rate across 347 mutations).
  - Raised `mcp-server` JaCoCo branch coverage threshold from 55% to **65%** (achieving 82% branch coverage and 93% instruction coverage).
  - Expanded `mcp-server` test suite from 80 to **165 tests** with 0 failures and 0 errors.
  - Managed `maven-jar-plugin` version (3.4.2) in root POM plugin management, eliminating Maven model warnings.
- **Build Architecture & Hygiene**:
  - Formally retired obsolete `native` module from the Maven reactor following the architectural transition to `jlink`/`jpackage`.
  - Captured unhandled runtime exceptions with standard JSON-RPC `-32603` (Internal Error) responses instead of unhandled thread terminations.
  - Bound SLF4J logging strictly to `System.err` via `simplelogger.properties` in `mcp-server` to keep `stdout` pure for JSON-RPC transport framing.
  - Centralized test dependencies in root POM using `testcontainers-bom` to prevent fractured partial updates.
- **Accurate Project Positioning**:
  - Updated documentation to emphasize "Zero runtime framework overhead with self-contained packaging and zero target-application modifications" over generic "zero-dependency" claims.

### Fixed
- **Null Map Key Serialization in `SimpleJson`** (#164): Serializes maps containing `null` keys as `"null": <value>` instead of throwing `NullPointerException`.
- **PostgreSQL JDBC Security Hardening** (#160): Expanded JDBC URI validation in `PostgresSchemaReader` to block CVE-2022-21724 attack vectors and dangerous properties (`loggerFile`, `loggerLevel`, `allowEncodingChanges`, `socketFactoryArg`, `sslFactoryArg`).
- **Resilient Numeric Parsing for LLM Tool Call Arguments** (#162): `ToolArgs.getLongArg()` and `getIntArg()` now parse string-formatted numbers returned by LLMs (e.g. `"12345"`).
- **Descriptive Error Formatting for Missing Beans** (#112): `SpringBeansToolHandler.get_bean_detail` now returns `{ "isError": true, "content": [...] }` when the requested bean is not found instead of returning an empty or ambiguous payload.
- **Actuator Command Usability & Security** (#165, #167, #115):
  - Sanitized validation messages in `ActuatorAuth` to be generic and leak-proof.
  - Added CLI option aliases `--actuator-user`, `--actuator-password`, `--actuator-token` to `ActuatorCommand`.
  - Added loopback URL detection to eliminate false unencrypted HTTP warnings when querying local loopback addresses (`127.0.0.1`, `::1`).
  - Added whitespace trimming for comma-separated metrics in `ActuatorCommand` and `ActuatorToolHandler`.
- **McpTransport Executor Hygiene & Charset Handling** (#61): Wrapped executor termination in `finally` to prevent executor thread leaks on early I/O errors and ensured UTF-8 `PrintStream` wrapping.
- **CLI VirtualMachine Detach Lifecycle** (#55): Protected `MemoryCommand` and `ThreadsCommand` with guaranteed `finally` detachment when JMX initialization fails.
- **Actuator Transparent GZIP & Deflate Decompression** (#103): Supported transparent GZIP and Deflate streaming decompression in `ActuatorClient` with decompression bomb protection.
- **BeansCommand Environment Variable Support** (#161): Supported `ACTUATOR_USER`, `ACTUATOR_PASSWORD`, `ACTUATOR_TOKEN` in `BeansCommand`.
- **Universal Installer Fresh macOS PATH Persistence** (#62): `install.sh` now creates `~/.zshrc` or `~/.bashrc` when neither exists and provides clear diagnostics on 404 release downloads.
- **End-to-End Spring Boot & MCP Integration Testing**: Added `SpringBootMockE2EIntegrationTest` validating MCP handshake, tool listing, actuator health, metrics with GZIP decompression, and Spring beans inspection over JSON-RPC.
- **Parseable Deadlock Report in `get_thread_diagnostics`**: the second content block is now the deadlock report's JSON alone. It used to start with `Deadlock Report: `, so a client parsing each block as JSON failed on it.
- **Thread Summary with No Thread IDs**: `ThreadMXBeanClient.getThreadSummary` treats a `null` from `getAllThreadIds()` as no threads and returns zero state counts instead of throwing `NullPointerException`.
- **Heap Usage Ratio in `MemoryPressure`**: `heapUsageRatio` now holds the heap's own used/max ratio. It used to carry the larger of the heap and Metaspace/Class Space ratios, so a full Metaspace over a near-idle heap read as 96% heap usage. The pressure level and recommendation still follow the fuller of the two.
- **Main Class of Modular Launches**: `jvm-mcp list` and `list_jvms` now show `module/Main` for a JVM started with `-m`, `--module` or `--module=`, instead of the flag itself (`-m`).
- **Actuator Port from Command-Line Arguments**: `ActuatorProbe` now also reads `--server.port=` and `--management.server.port=` (or their `-D` forms) from `sun.java.command`, so a service started with `java -jar app.jar --server.port=8085` is found on 8085 instead of only 8080, 8081 and 9090.
- **Array Types in Heap Histograms**: `get_heap_histogram` and `jvm-mcp memory --histogram` now name arrays as Java types (`byte[]`, `java.lang.String[]`, `java.lang.Object[][]`) instead of the JVM descriptors `jcmd` prints (`[B`, `[Ljava.lang.String;`, `[[Ljava.lang.Object;`).
- **Credentials in Slow-Query Output**: `find_slow_queries` and `jvm-mcp pg` now pass each query through `SensitiveDataRedactor`, so bearer and basic tokens, API keys, JWTs and JDBC credentials in a slow query are redacted, not only `password = '...'` literals.
- **Mixed-Case Schema Names in PostgreSQL Tools**: `validateSchemaName` now folds the name to lowercase, as PostgreSQL does for unquoted identifiers, so `--schema Sales` finds the tables of `sales` instead of reporting none.
- **Defensive JSON Parsing in `SimpleJson`**: Malformed numbers (`-`, `1.`, `1e`, `-1.5e-`) and truncated or non-hexadecimal `\u` escapes now fail with a positioned `IllegalArgumentException` instead of leaking `NumberFormatException`; non-ASCII digits are no longer routed into the number parser.
- **Unbounded JVM Memory Reporting**: `MemoryUsageInfo` now reports `maxMb` and `usedPercent` as `-1` when JMX exposes no upper bound (`max == -1`, e.g. non-heap memory or an unbounded pool such as Metaspace) instead of substituting committed bytes as the limit; `jvm-mcp memory` renders those quotas as `unbounded` (with `N/A` percent where a ratio is shown). A `max` of `0` remains a real numeric bound and existing bounded output is unchanged.

### Removed
- **Redundant Scripts**: Removed obsolete `scripts/install.sh` and duplicate `.github/homebrew/jvm-mcp.rb`.

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
