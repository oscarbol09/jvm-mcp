package dev.jvmmcp.core.attach;

import dev.jvmmcp.core.model.Framework;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Heuristic detector for identifying common JVM frameworks from process metadata. */
public final class FrameworkDetector {

    private static final Pattern JAR_LAUNCH_PATTERN =
            Pattern.compile(
                    "(?:^|\\s)-jar\\s+(?:\"([^\"]+\\.jar)\"|([^\\s]+\\.jar))(?:\\s|$)",
                    Pattern.CASE_INSENSITIVE);

    private FrameworkDetector() {}

    public static Framework detect(String displayName, String mainClass) {
        return detect(displayName, mainClass, null);
    }

    public static Framework detect(
            String displayName, String mainClass, Path targetWorkingDirectory) {
        String className = mainClass == null ? "" : mainClass.trim();
        if (className.isEmpty()) {
            className = firstCommandToken(displayName);
        }
        String normalizedClassName = className.toLowerCase();

        if (normalizedClassName.startsWith("org.springframework.boot.")) {
            return Framework.SPRING_BOOT;
        }
        if (normalizedClassName.startsWith("io.quarkus.runner.")
                || normalizedClassName.equals("io.quarkus.bootstrap.runner.quarkusentrypoint")) {
            return Framework.QUARKUS;
        }
        if (normalizedClassName.startsWith("io.micronaut.")) {
            return Framework.MICRONAUT;
        }

        // Try manifest detection if it's a jar execution
        String jarPath = extractJarPath(displayName);
        if (jarPath != null) {
            try {
                Path p = Path.of(jarPath);
                if (!p.isAbsolute()) {
                    if (targetWorkingDirectory == null) {
                        return Framework.PLAIN_JAVA;
                    }
                    p = targetWorkingDirectory.resolve(p).normalize();
                }
                if (Files.isRegularFile(p)) {
                    try (JarFile jar = new JarFile(p.toFile())) {
                        Manifest manifest = jar.getManifest();
                        if (manifest != null) {
                            Attributes attrs = manifest.getMainAttributes();
                            if (attrs.getValue("Spring-Boot-Version") != null
                                    || attrs.getValue("Start-Class") != null) {
                                return Framework.SPRING_BOOT;
                            }
                            if (attrs.getValue("Quarkus-Version") != null
                                    || "io.quarkus.bootstrap.runner.QuarkusEntryPoint"
                                            .equals(attrs.getValue("Main-Class"))) {
                                return Framework.QUARKUS;
                            }
                            if (attrs.getValue("Micronaut-Version") != null) {
                                return Framework.MICRONAUT;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Ignore exceptions during heuristic detection (e.g. InvalidPathException or
                // IOException)
            }
        }

        if (displayName != null && !displayName.isBlank()) {
            return Framework.PLAIN_JAVA;
        }
        return Framework.UNKNOWN;
    }

    private static String extractJarPath(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String command = displayName.trim();
        Matcher matcher = JAR_LAUNCH_PATTERN.matcher(command);
        if (matcher.find()) {
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }

        String unquoted = command.replace("\"", "").trim();
        return !unquoted.contains(" ") && unquoted.toLowerCase().endsWith(".jar") ? unquoted : null;
    }

    private static String firstCommandToken(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }
        String command = displayName.trim();
        if (command.startsWith("\"")) {
            int endQuote = command.indexOf('"', 1);
            return endQuote > 0 ? command.substring(1, endQuote) : command;
        }
        int space = command.indexOf(' ');
        return space > 0 ? command.substring(0, space) : command;
    }
}
