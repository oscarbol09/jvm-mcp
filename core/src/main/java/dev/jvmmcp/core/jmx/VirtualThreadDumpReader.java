package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.ThreadDetail;
import dev.jvmmcp.core.model.ThreadStackFrame;
import dev.jvmmcp.core.model.VirtualThreadSummary;
import dev.jvmmcp.core.util.SimpleJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * Diagnostic reader capturing and parsing HotSpot virtual thread dumps via the {@code
 * com.sun.management:type=DiagnosticCommand} MBean ({@code Thread.dump_to_file -format=json}).
 *
 * <p>HotSpot Java 21+ records virtual threads inside thread containers within the JSON thread dump.
 * Platform threads are distinguished from virtual threads using the set of live platform thread IDs
 * from {@link java.lang.management.ThreadMXBean#getAllThreadIds()}, ensuring no double-counting.
 */
public class VirtualThreadDumpReader {

    static final long MAX_DUMP_BYTES = 16L * 1024 * 1024;

    public static final class DumpTooLargeException extends RuntimeException {
        DumpTooLargeException(long actualBytes, long maxBytes) {
            super(
                    "Virtual-thread dump exceeds the maximum size of "
                            + maxBytes
                            + " bytes (actual size: "
                            + actualBytes
                            + " bytes)");
        }
    }

    private static final String DIAGNOSTIC_COMMAND_MBEAN =
            "com.sun.management:type=DiagnosticCommand";
    private static final String THREAD_DUMP_OPERATION = "threadDumpToFile";

    private final MBeanServerConnection mbsc;

    public VirtualThreadDumpReader(MBeanServerConnection mbsc) {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null.");
        }
        this.mbsc = mbsc;
    }

    /**
     * Reads a diagnostic snapshot and computes the {@link VirtualThreadSummary}.
     *
     * @param platformThreadIds live platform thread IDs from ThreadMXBean, used to disambiguate
     *     platform threads from virtual threads
     * @return present {@link VirtualThreadSummary} if supported and readable; {@link
     *     Optional#empty()} if unsupported (e.g. pre-Java 21, non-HotSpot), permission denied, or
     *     reading failed
     */
    public Optional<VirtualThreadSummary> readVirtualThreadSummary(long[] platformThreadIds) {
        Optional<Map<String, Object>> dumpJsonOpt = readDumpJson();
        if (dumpJsonOpt.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> platformSet = toPlatformSet(platformThreadIds);
        return Optional.ofNullable(parseVirtualThreadSummary(dumpJsonOpt.get(), platformSet));
    }

    /**
     * Reads a diagnostic snapshot and returns the list of observed virtual {@link ThreadDetail}s.
     *
     * @param platformThreadIds live platform thread IDs from ThreadMXBean
     * @return present list of virtual thread details, or {@link Optional#empty()} if unsupported or
     *     reading failed
     */
    public Optional<List<ThreadDetail>> readVirtualThreads(long[] platformThreadIds) {
        Optional<Map<String, Object>> dumpJsonOpt = readDumpJson();
        if (dumpJsonOpt.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> platformSet = toPlatformSet(platformThreadIds);
        return Optional.ofNullable(parseVirtualThreads(dumpJsonOpt.get(), platformSet));
    }

    /**
     * Executes {@code Thread.dump_to_file -format=json} to a temporary file, parses the JSON
     * payload, and guarantees deterministic deletion of the temporary file on success and failure.
     */
    Optional<Map<String, Object>> readDumpJson() {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("jvm-mcp-threads-", ".json");
            String pathString = tempFile.toAbsolutePath().toString();

            mbsc.invoke(
                    new ObjectName(DIAGNOSTIC_COMMAND_MBEAN),
                    THREAD_DUMP_OPERATION,
                    new Object[] {new String[] {"-format=json", "-overwrite", pathString}},
                    new String[] {String[].class.getName()});

            if (!Files.exists(tempFile)) {
                return Optional.empty();
            }

            long dumpSize = Files.size(tempFile);
            if (dumpSize > MAX_DUMP_BYTES) {
                throw new DumpTooLargeException(dumpSize, MAX_DUMP_BYTES);
            }

            String content = Files.readString(tempFile, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(SimpleJson.parseObject(content));
        } catch (DumpTooLargeException e) {
            throw e;
        } catch (Exception e) {
            // Unsupported JVM, permission failure, MBean missing, I/O error, or malformed JSON
            return Optional.empty();
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Parses the virtual thread summary from a pre-parsed thread dump JSON map.
     *
     * @param dumpJson parsed JSON map
     * @param platformThreadIds set of live platform thread IDs
     * @return summary of virtual threads, or {@code null} if the dump payload is invalid
     */
    public VirtualThreadSummary parseVirtualThreadSummary(
            Map<String, Object> dumpJson, Set<Long> platformThreadIds) {
        if (dumpJson == null) {
            return null;
        }
        Object threadDumpObj = dumpJson.get("threadDump");
        if (!(threadDumpObj instanceof Map<?, ?> threadDumpMap)) {
            return null;
        }
        Object containersObj = threadDumpMap.get("threadContainers");
        if (!(containersObj instanceof List<?> containersList)) {
            return null;
        }

        Set<Long> seenVirtualThreadIds = new HashSet<>();
        for (Object containerObj : containersList) {
            if (!(containerObj instanceof Map<?, ?> containerMap)) {
                continue;
            }
            Object threadsObj = containerMap.get("threads");
            if (!(threadsObj instanceof List<?> threadsList)) {
                continue;
            }
            for (Object threadObj : threadsList) {
                if (!(threadObj instanceof Map<?, ?> threadMap)) {
                    continue;
                }
                Long tid = extractThreadId(threadMap);
                if (tid == null) {
                    continue;
                }
                if (isVirtualThread(tid, threadMap, platformThreadIds)) {
                    seenVirtualThreadIds.add(tid);
                }
            }
        }
        return new VirtualThreadSummary(seenVirtualThreadIds.size());
    }

    /**
     * Parses the list of virtual {@link ThreadDetail} objects from a pre-parsed thread dump JSON
     * map.
     *
     * @param dumpJson parsed JSON map
     * @param platformThreadIds set of live platform thread IDs
     * @return list of virtual thread details, or {@code null} if the dump payload is invalid
     */
    public List<ThreadDetail> parseVirtualThreads(
            Map<String, Object> dumpJson, Set<Long> platformThreadIds) {
        if (dumpJson == null) {
            return null;
        }
        Object threadDumpObj = dumpJson.get("threadDump");
        if (!(threadDumpObj instanceof Map<?, ?> threadDumpMap)) {
            return null;
        }
        Object containersObj = threadDumpMap.get("threadContainers");
        if (!(containersObj instanceof List<?> containersList)) {
            return null;
        }

        List<ThreadDetail> virtualThreads = new ArrayList<>();
        Set<Long> seenVirtualThreadIds = new HashSet<>();

        for (Object containerObj : containersList) {
            if (!(containerObj instanceof Map<?, ?> containerMap)) {
                continue;
            }
            Object threadsObj = containerMap.get("threads");
            if (!(threadsObj instanceof List<?> threadsList)) {
                continue;
            }
            for (Object threadObj : threadsList) {
                if (!(threadObj instanceof Map<?, ?> threadMap)) {
                    continue;
                }
                Long tid = extractThreadId(threadMap);
                if (tid == null || seenVirtualThreadIds.contains(tid)) {
                    continue;
                }
                if (isVirtualThread(tid, threadMap, platformThreadIds)) {
                    seenVirtualThreadIds.add(tid);
                    String name =
                            threadMap.get("name") != null
                                    ? threadMap.get("name").toString()
                                    : "VirtualThread-" + tid;
                    List<ThreadStackFrame> stackFrames = new ArrayList<>();
                    Object stackObj = threadMap.get("stack");
                    if (stackObj instanceof List<?> stackList) {
                        for (Object frameObj : stackList) {
                            if (frameObj != null) {
                                stackFrames.add(ThreadStackFrame.parse(frameObj.toString()));
                            }
                        }
                    }
                    virtualThreads.add(
                            new ThreadDetail(
                                    tid,
                                    name,
                                    "VIRTUAL",
                                    null,
                                    null,
                                    null,
                                    0L,
                                    0L,
                                    0L,
                                    0L,
                                    false,
                                    false,
                                    stackFrames));
                }
            }
        }
        return virtualThreads;
    }

    private boolean isVirtualThread(Long tid, Map<?, ?> threadMap, Set<Long> platformThreadIds) {
        if (platformThreadIds != null && !platformThreadIds.isEmpty()) {
            return !platformThreadIds.contains(tid);
        }
        // Fallback when platformThreadIds is unavailable: inspect root stack frames
        Object stackObj = threadMap.get("stack");
        if (stackObj instanceof List<?> stackList && !stackList.isEmpty()) {
            for (Object frame : stackList) {
                if (frame != null && frame.toString().contains("java.lang.VirtualThread.run")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Long extractThreadId(Map<?, ?> threadMap) {
        Object tidObj = threadMap.get("tid");
        if (tidObj == null) {
            return null;
        }
        if (tidObj instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(tidObj.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Set<Long> toPlatformSet(long[] platformThreadIds) {
        if (platformThreadIds == null) {
            return Set.of();
        }
        Set<Long> set = new HashSet<>(platformThreadIds.length);
        for (long id : platformThreadIds) {
            set.add(id);
        }
        return set;
    }
}
