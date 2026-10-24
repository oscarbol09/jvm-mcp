# Contributing to JVM-MCP

Thank you for contributing to JVM-MCP! We welcome issues, architectural feedback, and pull requests from developers passionate about JVM runtime diagnostics and AI agent tooling.

---

## 1. Core Architectural Guidelines

Before writing code, please review these foundational design decisions:

1. **Pure Java Core (`core` module):**
   - The `core` module must remain free of heavy external frameworks (no Spring, no ORMs, no Jackson/Gson).
   - Use Java 21 features idiomatically: `record` for immutable DTOs, `switch` pattern matching, and standard JDK APIs (`com.sun.tools.attach`, `javax.management`, `java.net.http`).

2. **Modular Concurrency & Protocol Engine (`mcp-server` module):**
   - The MCP server operates strictly over `stdio` using an in-house JSON-RPC 2.0 engine on Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`). Cold start must remain `< 150ms`.
   - The server follows a clean, decoupled architecture:
     - `dev.jvmmcp.server.transport.McpTransport`: Manages the non-blocking `stdio` loop, virtual threads, and synchronized `stdout` response frames.
     - `dev.jvmmcp.server.protocol.McpDispatcher`: Handles JSON-RPC protocol negotiation (`initialize`, `ping`, `tools/list`, `tools/call`) and error taxonomy.
     - `dev.jvmmcp.server.tool.ToolRegistry` & `ToolHandler`: Pluggable domain handlers implementing `execute(toolName, args, ...)`.
     - `dev.jvmmcp.ServeCommand`: Thin Picocli entry point (< 70 lines) that orchestrates startup, shutdown hooks, and transport execution.
   - Keep the CLI lean and zero-framework: do not introduce external application frameworks (no Spring Boot runtime, Quarkus, or heavy JSON dependencies).

3. **Jlink & Jpackage Custom Runtime Architecture:**
   - The Attach API requires HotSpot native library bindings (`attach.dll` / `libattach.so`), so standalone distribution is packaged via `jlink` custom-stripped JREs and `jpackage` installers.
   - Avoid unchecked dynamic reflection or bloated dependencies in `core` and `mcp-server` to keep binary size minimal and startup fast.

4. **Resource Safety & Graceful Failures:**
   - All JMX connections and Attach handles must implement deterministic cleanup (`try-with-resources` or explicit `detach()`).
   - Never throw raw runtime exceptions to the MCP client; wrap errors in structured diagnostic responses (`AttachResult`, `DiagnosticReport`) or standard JSON-RPC error frames.

---

## 2. Local Development & Setup

### Prerequisites
* **JDK 21+** (Temurin or OpenJDK).
* Git.

### Building the Monorepo
```bash
# Build all modules, run surefire tests, and verify JaCoCo / PIT quality gates
./mvnw clean verify

# Run CLI locally
java -jar mcp-server/target/jvm-mcp-server-1.1.1.jar list
```

---

## 3. How to Implement a New MCP Tool

1. **Define Domain Models in `core/`:**
   Create an immutable `record` in `dev.jvmmcp.core.model` representing the structured diagnostic data.

2. **Implement the Client/Reader:**
   Place JMX readers in `dev.jvmmcp.core.jmx` or HTTP clients in `dev.jvmmcp.core.spring`. Ensure it takes an attached `VirtualMachine` or connection handle and returns domain records.

3. **Write Unit & Integration Tests in `core/`:**
   Add tests under `core/src/test/java/` verifying behavior, including mock processes or error scenarios (e.g., target MBean missing).

4. **Implement or Extend a `ToolHandler` in `mcp-server/`:**
   - Place domain handlers under `dev.jvmmcp.server.tool.*` (e.g., `JvmDiagnosticToolHandler`, `SpringBeansToolHandler`, `PostgresToolHandler`, `ActuatorToolHandler` or a new class implementing `ToolHandler`).
   - Declare the tool name in `toolNames()`, JSON Schema in `toolDefinitions()`, and execution logic in `execute(toolName, args, pid, vm, attachService)`.
   - Use `ToolArgs` for type-safe parameter extraction (`getStringArg`, `getLongArg`, etc.).
   - Register the handler in `ToolRegistry` within `ServeCommand#call()`.
   - Add unit tests under `mcp-server/src/test/java/dev/jvmmcp/server/tool/`.
   - If exposing as a standalone CLI command, implement a `@Command` class and register it under `@CommandLine.Command(subcommands = { ... })` in `JvmMcp.java`.

---

## 4. Commit & Pull Request Guidelines

### Commit Format (Conventional Commits)
Use atomic commits with descriptive, human-written messages explaining the **why**:
* `feat(heap): add live heap histogram reader via attach api`
* `fix(jmx): resolve memory leak when detaching from target vm`
* `docs(readme): clarify docker permission constraints`
* `test(threads): add deadlock reproduction integration test`

### Pull Request Rules
* Keep PRs focused on a single concern.
* Ensure `./mvnw clean verify` passes locally before submitting.
* Write a concise summary explaining **what** changed and **why** (avoid AI-generated filler paragraphs).
* Link any relevant issues (`Fixes #12`).
