# JVM-MCP

> **Self-contained Java 21 Model Context Protocol (MCP) server with zero runtime framework overhead.**  
> Provide Claude, Cursor, and AI coding assistants with real-time runtime context on memory, threads, Spring beans, and database connection pools without modifying target application code.

[![CI](https://github.com/oscarbol09/jvm-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/oscarbol09/jvm-mcp/actions/workflows/ci.yml)
[![Java Version](https://img.shields.io/badge/Java-21+-007396?logo=openjdk&logoColor=white)](https://jdk.java.net/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Table of Contents

- [Why This Exists](#why-this-exists)
- [How It Works](#how-it-works)
- [Quickstart: MCP Client Setup](#quickstart-mcp-client-setup)
- [CLI Usage](#cli-usage)
- [Exposed MCP Tools](#exposed-mcp-tools)
- [Architectural Principles](#architectural-principles)
- [Security & Defense Boundaries](#security--defense-boundaries)
- [Platform & Runtime Compatibility Matrix](#platform--runtime-compatibility-matrix)
- [Known Limitations and Trade-offs](#known-limitations-and-trade-offs)
- [Contributing & Governance](#contributing--governance)
- [License](#license)

---

## Why This Exists

When debugging backend Java services with LLMs, static source code only tells half the story:
1. Deadlocks, thread starvation, and runaway thread allocations cannot be deduced purely from code syntax.
2. Memory leaks in Old Gen or sudden allocation spikes in Eden require live heap inspection.
3. Spring Boot bean initialization orders and active HikariCP pool saturation require dynamic runtime context.

Existing tools require heavy external APM agents, modifying `pom.xml` files to expose open Actuator/JMX endpoints, or manually running `jstack` / `jcmd` in a terminal and copy-pasting megabytes of unformatted text into prompts.

**JVM-MCP** attaches locally to target JVM processes using the standard JDK Attach API (`com.sun.tools.attach`), dynamically exposes diagnostic data over the standardized Model Context Protocol (JSON-RPC 2.0 stdio), and requires **zero modifications** to target applications.

---

## How It Works

Distributed as a standalone multi-platform bundle with a stripped custom runtime via `jlink`/`jpackage`. No pre-installed JDK is required on the client machine.

```text
+-----------------------------------------+
|      Claude Desktop / Cursor / IDE      |
+-----------------------------------------+
                     | JSON-RPC 2.0 (stdio)
                     v
+-----------------------------------------+
|               jvm-mcp                   | (Virtual Threads, < 150ms cold start)
+-----------------------------------------+
                     | Attach API / Dynamic JMX
                     v
+-----------------------------------------+
|       Target Java App (PID 45231)       | (Zero modifications or dependencies)
+-----------------------------------------+
```

---

## Installation

Pre-built standalone distributions include a minimal modular JRE via `jlink` (Linux/macOS) and `jpackage` (Windows). No pre-installed JDK is required on the host system.

### Recommended: Automated One-Line Installers

#### Linux & macOS
```bash
curl -fsSL https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/install.sh | bash
```
*Options supported: `--version <v1.1.0>`, `--uninstall`, `--install-dir <dir>`, `--bin-dir <dir>`.*

#### Windows (PowerShell)

Quick install:
```powershell
irm https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/install.ps1 | iex
```

Custom installation with arguments (specific version or custom directory):
```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/install.ps1))) -Version 1.1.1 -InstallDir "$env:LOCALAPPDATA\jvm-mcp"
```

---

### Package Managers

#### Homebrew (macOS & Linux)
```bash
brew tap oscarbol09/tap
brew install jvm-mcp
```
*(Or direct formula install: `brew install https://github.com/oscarbol09/jvm-mcp/releases/latest/download/jvm-mcp.rb`)*

#### Scoop (Windows)
```powershell
scoop bucket add oscarbol09 https://github.com/oscarbol09/homebrew-tap.git
scoop install jvm-mcp
```
*(Or single-step direct install: `scoop install https://raw.githubusercontent.com/oscarbol09/homebrew-tap/main/bucket/jvm-mcp.json`)*

---

### Verification: First Step After Installation

Immediately after installation, run the diagnostic tool to verify Java HotSpot runtime compatibility, user process boundaries, and Dynamic Attach API capabilities:

```bash
jvm-mcp doctor
```

Expected output:
```text
JVM-MCP Doctor
--------------
OS Name:       Linux / macOS / Windows
[OK] Java version 21 meets the minimum requirement (Java 21+).
[OK] HotSpot-compatible JVM detected. Dynamic Attach API fully supported.
[OK] Attach API is functioning.
[OK] Found local Java processes accessible to user.

Your system is ready to use JVM-MCP.
```

---

### Operating System Permissions & Troubleshooting

| Platform / Environment | Requirement & Operational Guidance |
| :--- | :--- |
| **All Platforms (User Isolation)** | Target JVM processes must run under the **same operating system user** as `jvm-mcp`. For security reasons, cross-user process attachment is blocked by JVM runtime specifications. |
| **Linux (`ptrace_scope`)** | Linux kernel YAMA restrictions may restrict dynamic attachment. Ensure `/proc/sys/kernel/yama/ptrace_scope` is `0` or grant `CAP_SYS_PTRACE`. |
| **Docker Containers** | When monitoring processes inside containers, run with `--pid=host` to share the host process namespace and `--cap-add=SYS_PTRACE`. |
| **macOS (SIP & Hardened Runtime)** | macOS process security requires `jvm-mcp` to run under the same interactive user session. |
| **Headless / Remote Spring Boot** | When dynamic Attach API is restricted by environment security policies, use **Actuator Remote Mode** (`get_actuator_health`, `get_actuator_metrics`) via standard HTTP/HTTPS without requiring local Attach API access. |

---

### Updating & Uninstallation

#### Updating
- **Linux & macOS**: Re-run the installer: `curl -fsSL https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/install.sh | bash`
- **Homebrew**: `brew update && brew upgrade jvm-mcp`
- **Windows (PowerShell)**: Re-run: `irm https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/install.ps1 | iex`
- **Scoop**: `scoop update jvm-mcp`

#### Uninstallation
- **Linux & macOS**:
  ```bash
  curl -fsSL https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/uninstall.sh | bash
  # Or: ./install.sh --uninstall
  ```
- **Windows (PowerShell)**:
  ```powershell
  irm https://raw.githubusercontent.com/oscarbol09/jvm-mcp/main/uninstall.ps1 | iex
  # Or: .\install.ps1 -Uninstall
  ```
- **Homebrew**: `brew uninstall jvm-mcp`
- **Scoop**: `scoop uninstall jvm-mcp`

---

### Alternative Channels

#### Docker / OCI Container (GHCR)

The official container image is optimized for remote diagnostic workflows (inspecting PostgreSQL schemas and Spring Boot Actuator endpoints over network):

```bash
docker run -i --rm ghcr.io/oscarbol09/jvm-mcp:1.1.1 serve
```

> **Note on Local Attach API**: The container runs under an unprivileged user (`jvmuser`, UID 10001) with a minimal JRE. Dynamic Attach API inspection of host processes from inside Docker requires host PID namespace sharing (`--pid=host`), `CAP_SYS_PTRACE`, and matching user boundaries. For local JVM introspection, pre-compiled standalone archives or native installers are recommended.

#### Pre-Compiled Standalone Archives

Download release packages and corresponding `.sha256` checksums from [GitHub Releases](https://github.com/oscarbol09/jvm-mcp/releases):

| Platform / Architecture | Archive Name | Runtime Packaging |
| :--- | :--- | :--- |
| Linux (x86_64 / amd64) | `jvm-mcp-linux-amd64.tar.gz` | Modular JRE (`jlink`) + symlink-safe launcher |
| Linux (ARM64 / aarch64) | `jvm-mcp-linux-aarch64.tar.gz` | Modular JRE (`jlink`) + symlink-safe launcher |
| macOS (Apple Silicon ARM64) | `jvm-mcp-macos-aarch64.tar.gz` | Modular JRE (`jlink`) + symlink-safe launcher |
| macOS (Intel x86_64) | `jvm-mcp-macos-amd64.tar.gz` | Modular JRE (`jlink`) + symlink-safe launcher |
| Windows (x86_64 / amd64) | `jvm-mcp-windows-amd64.zip` | Standalone application image (`jpackage` .exe) |

Verify checksum and unpack:
```bash
# Example for Linux
sha256sum -c jvm-mcp-linux-amd64.tar.gz.sha256
tar -xzf jvm-mcp-linux-amd64.tar.gz
./jvm-mcp --version
```

#### Building From Source (Developers)

Requires JDK 21+ and Git:
```bash
git clone https://github.com/oscarbol09/jvm-mcp.git
cd jvm-mcp
./mvnw clean package -DskipTests

# Run the packaged fat JAR directly:
java -jar mcp-server/target/jvm-mcp-server-1.1.1.jar --version
```

---

## Quickstart: MCP Client Setup

### Claude Desktop

Add `jvm-mcp` to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jvm-mcp": {
      "command": "jvm-mcp",
      "args": ["serve"]
    }
  }
}
```

*(If running from the repository fat JAR directly: `"command": "java", "args": ["-jar", "/path/to/jvm-mcp-server-1.1.1.jar", "serve"]`)*

### Cursor / Windsurf

1. Open **Settings > Features > MCP**.
2. Click **+ Add New MCP Server**.
3. Name: `jvm-mcp`
4. Type: `command`
5. Command: `jvm-mcp serve`

Once configured, ask your AI: *"What Java processes are running?"* or *"Analyze the memory and detect deadlocks in PID 12345"*.

---

## CLI Usage

`jvm-mcp` doubles as a standalone diagnostic terminal CLI for local inspection:

```bash
# Verify local environment readiness and Attach API permissions
jvm-mcp doctor

# Discover running Java processes and frameworks
jvm-mcp list

# Inspect memory pools, GC pauses, and heap histogram
jvm-mcp memory <pid>

# Diagnose thread states and detect deadlock cycles
jvm-mcp threads <pid>

# Inspect Spring Boot ApplicationContext beans
jvm-mcp beans <pid> --filter "*Service*"

# Introspect PostgreSQL schema and find missing indexes via JDBC
jvm-mcp pg --url "jdbc:postgresql://localhost:5432/mydb" --action missing-indexes

# Query remote Spring Boot Actuator endpoints
jvm-mcp actuator --url "http://localhost:8080" metrics
```

---

## Exposed MCP Tools

The server exposes 14 diagnostic tools to AI assistants:

| Tool Name | Description | Arguments |
| :--- | :--- | :--- |
| `list_jvms` | Lists running JVMs with PID, display name, and detected framework (Spring Boot, Quarkus, Micronaut). | *None* |
| `get_memory_summary` | Live heap/non-heap regions, GC pauses, and memory pressure. | `pid` (number) |
| `get_heap_histogram` | Top class instance counts and memory consumption via `jcmd`. | `pid` (number), `limit` (number, optional) |
| `get_thread_diagnostics` | Thread states breakdown and deadlock detection (inspects platform and mounted virtual threads; unmounted virtual threads tracked in #10). | `pid` (number) |
| `get_thread_dump` | Full formatted thread dump with lock stack traces. | `pid` (number) |
| `list_spring_beans` | Paginated Spring beans by context via JMX/Actuator, with `totalBeans` and `truncated`; `summary` returns per-context counts and top 10 packages instead. | `pid` (number), `filter` (string, optional), `limit` (integer, optional, default 200, max 2000), `offset` (integer, optional, default 0), `summary` (boolean, optional) |
| `get_bean_detail` | Detailed metadata, autowired dependencies, and scope of a bean. | `pid` (number), `beanName` (string) |
| `list_hikari_pools` | HikariCP pool metrics, active/idle connections, and leak warnings. Returns a note when no HikariCP MBeans are registered. | `pid` (number) |
| `inspect_pg_schema` | PostgreSQL table schema, columns, row estimates, and foreign keys. | `url` (string), `schema` (string, optional) |
| `find_missing_indexes` | High sequential scan tables with low index usage via `pg_stat_user_tables`. | `url` (string), `schema` (string, optional), `minTableSizeMb` (integer, optional) |
| `find_slow_queries` | Slow query execution profiles from `pg_stat_statements`. | `url` (string), `limit` (integer, optional) |
| `get_actuator_health` | Recursively parsed Spring Boot `/actuator/health` component status. | `url` (string) |
| `get_actuator_metrics` | Batch or individual diagnostic metric values from `/actuator/metrics`. | `url` (string), `metrics` (string, optional) |
| `get_actuator_startup` | Startup timeline and slowest beans from `/actuator/startup`. | `url` (string) |

`list_spring_beans` sorts contexts by id and beans by name, then applies `offset` and
`limit` across all contexts. Out-of-range values are clamped to the allowed range.

`get_bean_detail` matches literal bean names and aliases, preferring exact case-sensitive
matches and using a case-insensitive fallback only when unambiguous. Its report builder
retains only matching candidates across contexts. HTTP discovery still downloads and
parses the full Actuator payload; unrelated bean-detail records are not constructed.

`list_hikari_pools` returns a `pools` array plus an optional `note`. When HikariCP MBeans are not registered (the Spring Boot default), `pools` is empty and `note` explains how to expose them:

```json
{
  "pools": [],
  "note": "No HikariCP MBeans registered. Enable spring.datasource.hikari.register-mbeans=true or use get_actuator_metrics with hikaricp.connections.*"
}
```

When pools are available, `pools` contains the existing per-pool metrics and `note` is `null`.

Missing-index analysis defaults to a minimum table size of 1 MiB (1,048,576 bytes).
Set `minTableSizeMb` to `0` to include smaller tables, or choose a higher whole-number
threshold to filter them out. The CLI exposes the same setting:

```bash
jvm-mcp pg --url "jdbc:postgresql://localhost:5432/mydb" --action missing-indexes --min-table-size-mb 0
```

Java callers can use `findMissingIndexes(schemaName, minTableSizeBytes)` for a threshold in bytes.

Slow-query analysis returns the 20 statements with the highest mean execution time by default.
Set `limit` (or `--limit` on the CLI) to return between 1 and 100 of them:

```bash
jvm-mcp pg --url "jdbc:postgresql://localhost:5432/mydb" --action slow-queries --limit 5
```

---

## Architectural Principles

- **Virtual Threads & Thread-Safe Stdio:** Each incoming JSON-RPC request executes on a dedicated Java 21 Virtual Thread (`Executors.newVirtualThreadPerTaskExecutor()`). Outbound `stdout` writes are synchronized on a dedicated monitor lock to prevent frame interleaving.
- **Dynamic JDK Management Agent:** JMX access first checks the target JVM's `com.sun.management.jmxremote.localConnectorAddress` agent property. If no address is available, it calls `VirtualMachine.startLocalManagementAgent()` and rechecks the property. If the address still cannot be resolved, the connection fails with a diagnostic. No `management-agent.jar` fallback is used.
- **Zero Runtime Framework Overhead:** Operates with no heavy web or application frameworks (no Spring Boot runtime, Quarkus, Netty, Jackson, or reactive engines). It uses a lightweight custom JSON-RPC engine (`SimpleJson`), native JDK `HttpClient`, and Picocli for CLI execution, ensuring a cold start under 150ms.

---

## Security & Defense Boundaries

1. **OS Process Boundary & Privileged Access Control:** Enforces numeric PID validation (> 0), process liveness checks, and OS user matching (`ProcessHandle.info().user()`). Cross-user attachment without operating system elevation is strictly rejected.
2. **Automatic Sensitive Data Redaction:** All diagnostic tool outputs and execution error messages returned over MCP are filtered through `SensitiveDataRedactor` to sanitize Bearer tokens, Basic credentials, passwords, API keys, embedded JDBC passwords, and JWTs.
3. **Actuator SSRF & Path Traversal Mitigation:** Enforces strict `http`/`https` scheme validation, disables HTTP redirect following (`followRedirects: NEVER`) to eliminate cloud metadata/internal network pivoting, and rejects directory traversal in endpoints.
4. **PostgreSQL JDBC Hardening & Schema Validation:** Enforces `jdbc:postgresql://` protocol, validates loopback resolution, strips unsafe driver properties (`socketFactory`, `sslrootcert`, `options`), and validates schema names against standard SQL identifier patterns to prevent SQL injection.
5. **Resource Bounding & Execution Timeouts:** Heap histograms accept limits from 1 to 1,000 entries (default 100; parsing capped at 100,000 lines), arguments are bounded via `ToolArgs`, and all tool invocations execute with an enforced 15-second virtual thread timeout.
6. **ReDoS & Recursion Guards:** Sanitizes glob wildcards in `GlobMatcher` to prevent quadratic backtracking and bounds `SimpleJson` recursion depth to 100.

---

## Platform & Runtime Compatibility Matrix

| Runtime Environment | Architecture / Vendor | Status | Operational Notes |
| :--- | :--- | :--- | :--- |
| **Linux** | x86_64, aarch64 | Supported | Standard HotSpot Attach API. If `yama/ptrace_scope` is >= 1, non-child attach requires matching UID or `CAP_SYS_PTRACE`. |
| **macOS** | Apple Silicon (aarch64), Intel (x86_64) | Supported | Native `jlink` minimal runtime distribution. |
| **Windows** | x86_64 | Supported | Self-contained `.exe` image via `jpackage`. |
| **OpenJDK / HotSpot** | Temurin, Corretto, Zulu, Oracle, Liberica | Full Support | Standard `jdk.attach` implementation with dynamic local management agent. |
| **IBM Semeru / OpenJ9** | OpenJ9 / IBM J9 | Experimental | OpenJ9 uses `com.ibm.tools.attach`. Use Actuator HTTP mode if Attach API fails. |
| **Docker Containers** | Isolated namespaces | Conditional | Cross-namespace attach requires `--pid=host` or running `jvm-mcp` inside the container. Alternatively use Actuator mode. |

---

## Known Limitations and Trade-offs

- **Container Boundaries:** Direct host attachment across isolated Docker container namespaces fails due to separate PID and IPC namespaces. Attach from within the container namespace, use `--pid=host`, or use Actuator HTTP mode (`jvm-mcp actuator --url ...`).
- **Virtual Threads Visibility (Project Loom):** Current thread diagnostics leverage the standard JVM `ThreadMXBean`. In Java 21, unmounted virtual threads resting in continuation queues are not surfaced by `ThreadMXBean`. Comprehensive virtual thread inspection using HotSpot thread dumps / JVMTI is tracked under issue [#10](https://github.com/oscarbol09/jvm-mcp/issues/10).
- **Jlink / Jpackage Bundling:** GraalVM Native Image was evaluated and rejected because `jdk.attach` dynamically loads HotSpot C++ native libraries (`libattach.so` / `attach.dll`), violating Closed-World AOT assumptions. We distribute minimal bundles containing a stripped modular JRE via `jlink` (Linux/macOS) and `jpackage` app images (Windows), maintaining cross-platform parity with startup times under 150ms.

---

## Contributing & Governance

- [Discussions](https://github.com/oscarbol09/jvm-mcp/discussions): Ask questions, share MCP setups, and brainstorm ideas.
- [ROADMAP.md](ROADMAP.md): Milestones and active development phases.
- [CONTRIBUTING.md](CONTRIBUTING.md): Local environment setup, module architecture, and guide for adding MCP tools.
- [SECURITY.md](SECURITY.md): Vulnerability reporting policy and defense boundaries.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md): Community standards (Contributor Covenant).

---

## License

This project is licensed under the terms of the **MIT License**. See the [`LICENSE`](LICENSE) file for details.

Copyright (c) 2026 oscarbol09 / JVM-MCP Contributors.
