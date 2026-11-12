package dev.jvmmcp.server.tool;

import dev.jvmmcp.core.util.SensitiveDataRedactor;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages registration and lookup of ToolHandlers and delegates execution. Enforces per-tool
 * execution timeouts and automatic redaction of operational secrets.
 */
public class ToolRegistry implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_MAX_CONCURRENT_EXECUTIONS = 8;

    private final List<ToolHandler> registeredHandlers = new ArrayList<>();
    private final Map<String, ToolHandler> handlerMap = new LinkedHashMap<>();
    private final Duration toolTimeout;
    private final int maxConcurrentExecutions;
    private final Semaphore executionSlots;
    private final ExecutorService executorService;

    public ToolRegistry() {
        this(DEFAULT_TIMEOUT);
    }

    public ToolRegistry(Duration toolTimeout) {
        this(toolTimeout, DEFAULT_MAX_CONCURRENT_EXECUTIONS);
    }

    ToolRegistry(Duration toolTimeout, int maxConcurrentExecutions) {
        if (maxConcurrentExecutions <= 0) {
            throw new IllegalArgumentException("maxConcurrentExecutions must be positive");
        }
        this.toolTimeout = toolTimeout != null ? toolTimeout : DEFAULT_TIMEOUT;
        this.maxConcurrentExecutions = maxConcurrentExecutions;
        this.executionSlots = new Semaphore(maxConcurrentExecutions);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void register(ToolHandler handler) {
        if (handler == null) {
            return;
        }
        List<String> names = new ArrayList<>(handler.toolNames());
        Set<String> uniqueNames = new HashSet<>();
        // Validate the whole handler before changing either registration index.
        for (String name : names) {
            if (handlerMap.containsKey(name) || !uniqueNames.add(name)) {
                throw new IllegalArgumentException("Duplicate tool registration for: " + name);
            }
        }
        registeredHandlers.add(handler);
        for (String name : names) {
            handlerMap.put(name, handler);
        }
    }

    public Optional<ToolHandler> getHandler(String toolName) {
        if (toolName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlerMap.get(toolName));
    }

    public List<Map<String, Object>> getToolsList() {
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();
        for (ToolHandler handler : registeredHandlers) {
            for (Map<String, Object> definition : handler.toolDefinitions()) {
                tools.putIfAbsent((String) definition.get("name"), definition);
            }
        }
        return new ArrayList<>(tools.values());
    }

    public Map<String, Object> execute(String toolName, Map<String, Object> arguments) {
        ToolHandler handler = toolName != null ? handlerMap.get(toolName) : null;
        if (handler == null) {
            return Map.of(
                    "content",
                    List.of(Map.of("type", "text", "text", "Unknown tool: " + toolName)),
                    "isError",
                    true);
        }

        if (!executionSlots.tryAcquire()) {
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Tool execution rejected: concurrency limit reached (max "
                                            + maxConcurrentExecutions
                                            + ")")),
                    "isError",
                    true);
        }

        AtomicBoolean permitReleased = new AtomicBoolean(false);
        Runnable releasePermit =
                () -> {
                    if (permitReleased.compareAndSet(false, true)) {
                        executionSlots.release();
                    }
                };
        AtomicBoolean workerStarted = new AtomicBoolean(false);

        Future<Map<String, Object>> future;
        try {
            future =
                    executorService.submit(
                            () -> {
                                workerStarted.set(true);
                                try {
                                    return handler.execute(toolName, arguments);
                                } finally {
                                    releasePermit.run();
                                }
                            });
        } catch (RuntimeException e) {
            releasePermit.run();
            throw e;
        }

        try {
            Map<String, Object> rawResult =
                    future.get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return sanitizeResult(rawResult);
        } catch (InterruptedException e) {
            cancelWorker(future, workerStarted, releasePermit);
            Thread.currentThread().interrupt();
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Tool execution interrupted " + toolName)),
                    "isError",
                    true);

        } catch (TimeoutException e) {
            cancelWorker(future, workerStarted, releasePermit);
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Tool execution timed out after "
                                            + toolTimeout.toSeconds()
                                            + " seconds: "
                                            + toolName)),
                    "isError",
                    true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String sanitizedMsg =
                    SensitiveDataRedactor.redact(
                            cause.getMessage() != null ? cause.getMessage() : "Execution error");
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Error executing tool: " + sanitizedMsg)),
                    "isError",
                    true);
        } catch (Exception e) {
            cancelWorker(future, workerStarted, releasePermit);
            String sanitizedMsg =
                    SensitiveDataRedactor.redact(
                            e.getMessage() != null ? e.getMessage() : "Execution error");
            return Map.of(
                    "content",
                    List.of(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    "Error executing tool: " + sanitizedMsg)),
                    "isError",
                    true);
        }
    }

    private void cancelWorker(
            Future<?> future, AtomicBoolean workerStarted, Runnable releasePermit) {
        future.cancel(true);
        if (!workerStarted.get()) {
            releasePermit.run();
        }
    }

    @Override
    public void close() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeResult(Map<String, Object> result) {
        if (result == null) {
            return Map.of();
        }
        Object contentObj = result.get("content");
        if (!(contentObj instanceof List<?> list)) {
            return (Map<String, Object>) SensitiveDataRedactor.redactDeep(result);
        }

        List<Object> sanitizedList = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> mapItem) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapItem.entrySet()) {
                    String key = Objects.toString(entry.getKey(), "");
                    Object val = entry.getValue();
                    if ("text".equals(key) && val instanceof String textVal) {
                        copy.put(key, SensitiveDataRedactor.redact(textVal));
                    } else {
                        copy.put(key, SensitiveDataRedactor.redactDeep(val));
                    }
                }
                sanitizedList.add(copy);
            } else {
                sanitizedList.add(SensitiveDataRedactor.redactDeep(item));
            }
        }

        Map<String, Object> newResult = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if ("content".equals(entry.getKey())) {
                newResult.put("content", sanitizedList);
            } else {
                newResult.put(entry.getKey(), SensitiveDataRedactor.redactDeep(entry.getValue()));
            }
        }
        return newResult;
    }
}
