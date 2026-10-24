package dev.jvmmcp.core.model;

/**
 * Individual frame in a thread execution stack trace.
 */
public record ThreadStackFrame(
    String className,
    String methodName,
    String fileName,
    int lineNumber,
    boolean isNativeMethod
) {
    public static ThreadStackFrame from(StackTraceElement element) {
        if (element == null) {
            return new ThreadStackFrame("Unknown", "unknown", null, -1, false);
        }
        return new ThreadStackFrame(
            element.getClassName(),
            element.getMethodName(),
            element.getFileName(),
            element.getLineNumber(),
            element.isNativeMethod()
        );
    }

    /**
     * Parses a stack frame string (such as those emitted by HotSpot JSON thread dumps).
     *
     * @param text string representation of a stack trace line, e.g.
     *             {@code "java.base/java.lang.Thread.getStackTrace(Thread.java:2451)"}
     * @return parsed {@link ThreadStackFrame}
     */
    public static ThreadStackFrame parse(String text) {
        if (text == null || text.isBlank()) {
            return new ThreadStackFrame("Unknown", "unknown", null, -1, false);
        }
        String s = text.trim();
        int openParen = s.indexOf('(');
        int slashIdx = s.indexOf('/');
        if (slashIdx >= 0 && (openParen < 0 || slashIdx < openParen)) {
            s = s.substring(slashIdx + 1);
            openParen = s.indexOf('(');
        }
        int closeParen = s.lastIndexOf(')');
        if (openParen < 0 || closeParen <= openParen) {
            return new ThreadStackFrame("Unknown", s, null, -1, false);
        }
        String classAndMethod = s.substring(0, openParen);
        int lastDot = classAndMethod.lastIndexOf('.');
        String className = lastDot > 0 ? classAndMethod.substring(0, lastDot) : "Unknown";
        String methodName = lastDot >= 0 && lastDot < classAndMethod.length() - 1
            ? classAndMethod.substring(lastDot + 1) : classAndMethod;

        String sourceInfo = s.substring(openParen + 1, closeParen).trim();
        if ("Native Method".equalsIgnoreCase(sourceInfo)) {
            return new ThreadStackFrame(className, methodName, null, -2, true);
        }
        if ("Unknown Source".equalsIgnoreCase(sourceInfo)) {
            return new ThreadStackFrame(className, methodName, null, -1, false);
        }
        int colon = sourceInfo.lastIndexOf(':');
        if (colon > 0) {
            String fileName = sourceInfo.substring(0, colon);
            try {
                int lineNumber = Integer.parseInt(sourceInfo.substring(colon + 1));
                return new ThreadStackFrame(className, methodName, fileName, lineNumber, false);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ThreadStackFrame(className, methodName, sourceInfo.isEmpty() ? null : sourceInfo, -1, false);
    }

    @Override
    public String toString() {
        if (isNativeMethod) {
            return className + "." + methodName + "(Native Method)";
        }
        if (fileName != null && lineNumber >= 0) {
            return className + "." + methodName + "(" + fileName + ":" + lineNumber + ")";
        }
        return className + "." + methodName + "(Unknown Source)";
    }
}
