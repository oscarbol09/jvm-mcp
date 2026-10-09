# JVM-MCP

> **Native Java Model Context Protocol (MCP) server for live Spring Boot inspection.**  
> Provide Claude, Cursor, and Antigravity with real-time context on memory, threads, and database state without modifying your application code.

[![CI](https://github.com/oscarbol09/jvm-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/oscarbol09/jvm-mcp/actions/workflows/ci.yml)
[![Java Version](https://img.shields.io/badge/Java-21+-007396?logo=openjdk&logoColor=white)](https://jdk.java.net/21/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Good First Issues](https://img.shields.io/github/issues/oscarbol09/jvm-mcp/good%20first%20issue?color=7057ff&label=good%20first%20issues)](https://github.com/oscarbol09/jvm-mcp/issues)
[![GitHub Sponsors](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?logo=github-sponsors&logoColor=white)](https://github.com/sponsors/oscarbol09)
[![Support on Ko-Fi](https://img.shields.io/badge/Support-Ko--Fi-F16061?logo=ko-fi&logoColor=white)](https://ko-fi.com/oscarmb09)

---

## Table of Contents

- [The Blind AI Assistant Problem](#the-blind-ai-assistant-problem)
- [The Solution: Zero Friction](#the-solution-zero-friction)
- [Exposed Tools](#exposed-tools)
- [Architectural Principles](#architectural-principles)
- [Known Limitations and Trade-offs](#known-limitations-and-trade-offs)
- [Community and Contributing](#community-and-contributing)
- [License](#license)

---

## The Blind AI Assistant Problem

Every backend engineer debugging complex Java applications with AI assistants hits the same wall: **static source code does not reflect the live runtime state**.

```text
1. You ask Claude: "Why is the payment service timing out intermittently?"
2. The model inspects the code: "The logic looks fine, it might be a thread deadlock."
3. You cannot verify actual runtime thread state without leaving your editor.
4. You switch to terminal, execute `jstack <pid>`, copy the raw dump,
   paste it into chat, and struggle with context token limits.
```

Existing diagnostic approaches have significant drawbacks: Python scripts analyzing manual post-mortem dumps, or Node.js tools requiring developers to modify target `pom.xml` files to expose Actuator endpoints or remote JMX.

---

## The Solution: Zero Friction

**JVM-MCP** bridges this gap by attaching locally to the target JVM using the JDK **Attach API** (`com.sun.tools.attach`).

Distributed as a standalone native binary starting in `< 15ms`. No Node.js runtime required, no intermediate scripts, and critically: **zero modifications to your target application code**.

```
+---------------------------------------+
¦     Claude Desktop / Cursor / IDE     ¦
+---------------------------------------+
                   ¦ MCP Protocol (stdio)
                   ?
+---------------------------------------+
¦              jvm-mcp                  ¦ (Native Binary, < 15ms startup)
+---------------------------------------+
                   ¦ Attach API / JMX
                   ?
+---------------------------------------+
¦       Target Java App (PID 45231)     ¦ (No custom dependencies required)
+---------------------------------------+
```

---

## Quickstart

Configure your AI assistant to run the JVM-MCP server.

### Claude Desktop
Add the following to your claude_desktop_config.json:
`json
{
  "mcpServers": {
    "jvm-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/jvm-mcp-server.jar", "serve"]
    }
  }
}
`

### Cursor
1. Go to **Settings > Features > MCP**.
2. Click **+ Add New MCP Server**.
3. Name: jvm-mcp
4. Type: command
5. Command: java -jar /path/to/jvm-mcp-server.jar serve

Once configured, ask your AI: *"What Java processes are running?"* or *"Analyze the memory of PID <number>"*.

---
## Exposed Tools

Once attached, the LLM gains real-time diagnostic visibility:

- **Spring Boot Context:** Inspect instantiated beans (`list_spring_beans`) and identify slow bean initialization durations.
- **Memory & Heap:** Live heap region usage, GC pause counters (`get_heap_summary`), and top class instance counts.
- **Threads & Concurrency:** Clean, LLM-formatted thread dumps and automated deadlock cycle detection.
- **HikariCP:** Live connection pool metrics, connection leaks, and saturation alerts.
- **PostgreSQL:** Direct JDBC database schema introspection, sequential scan table stats, and slow query identification.

---

## Architectural Principles

- **Pure SDK CLI by Default:** The default CLI operates directly on the pure Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) via Picocli, avoiding Spring Boot bootstrap overhead to keep cold starts under 15ms.
- **Transparent Injection:** Bundles a `DiagnosticAgent` in internal resources, extracting it on demand to local user cache (`~/.cache/jvm-mcp/`) with SHA-256 verification when MBeans need to be loaded.

---

## Known Limitations and Trade-offs

- **OS Permission Boundaries:** By kernel design, the JDK Attach API requires `jvm-mcp` to run with matching UID permissions as the target JVM. If your target app runs inside an isolated Docker container, direct host attachment will fail. In those environments, use the `--actuator http://localhost:8080` mode or attach from within the container namespace.
- **OS-Matrix Distribution Parity:** GraalVM Native Image was aggressively abandoned across all platforms. The JDK Attach API (`jdk.attach`) requires dynamic loading of native C++ libraries at runtime (e.g., `libattach.so` or `attach.dll`), which fundamentally violates Closed-World AOT assumptions. Pragmatically, we distribute using `jlink` custom JREs for Linux/macOS and `jpackage` for zero-dependency Windows `.exe` installers, achieving cross-platform parity without sacrificing JVM introspection capabilities.



## Community and Contributing

JVM-MCP is an open-source project welcoming community contributions. Review our governance documentation:

- [ROADMAP.md](ROADMAP.md): Active development milestones and open issues labeled `good first issue` and `help wanted`.
- [CONTRIBUTING.md](CONTRIBUTING.md): Local environment setup, module architecture, and guide on implementing new MCP tools.
- [SECURITY.md](SECURITY.md): Vulnerability reporting policy and response timelines.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md): Community standards (Contributor Covenant).

---

## License

This project is licensed under the terms of the **MIT License**. See the [`LICENSE`](LICENSE) file for details.

Copyright (c) 2026 oscarbol09 / JVM-MCP Contributors.


