package dev.jvmmcp.core.attach;

import dev.jvmmcp.core.model.Framework;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Heuristic detector for identifying common JVM frameworks from process metadata.
 */
public final class FrameworkDetector {

    private FrameworkDetector() {}

    public static Framework detect(String displayName, String mainClass) {
        String combined = ((displayName != null ? displayName : "") + " " + (mainClass != null ? mainClass : "")).toLowerCase();

        if (combined.contains("org.springframework.boot") || combined.contains("springapplication") || combined.contains("springboot") || combined.contains("spring-boot")) {
            return Framework.SPRING_BOOT;
        }
        if (combined.contains("io.quarkus") || combined.contains("quarkus")) {
            return Framework.QUARKUS;
        }
        if (combined.contains("io.micronaut") || combined.contains("micronaut")) {
            return Framework.MICRONAUT;
        }

        // Try manifest detection if it's a jar execution
        if (displayName != null) {
            String command = displayName.trim().replace("\"", "");
            
            // Handle "-jar" prefix
            if (command.toLowerCase().startsWith("-jar ")) {
                command = command.substring(5).trim();
            }

            int jarIndex = command.toLowerCase().indexOf(".jar ");
            String jarPath = null;
            if (jarIndex != -1) {
                jarPath = command.substring(0, jarIndex + 4).trim();
            } else if (command.toLowerCase().endsWith(".jar")) {
                jarPath = command.trim();
            }

            if (jarPath != null) {
                try {
                    Path p = Path.of(jarPath);
                    if (Files.exists(p)) {
                        try (JarFile jar = new JarFile(p.toFile())) {
                            Manifest manifest = jar.getManifest();
                            if (manifest != null) {
                                Attributes attrs = manifest.getMainAttributes();
                                if (attrs.getValue("Spring-Boot-Version") != null || attrs.getValue("Start-Class") != null) {
                                    return Framework.SPRING_BOOT;
                                }
                                if (attrs.getValue("Quarkus-Version") != null || "io.quarkus.bootstrap.runner.QuarkusEntryPoint".equals(attrs.getValue("Main-Class"))) {
                                    return Framework.QUARKUS;
                                }
                                if (attrs.getValue("Micronaut-Version") != null) {
                                    return Framework.MICRONAUT;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore exceptions during heuristic detection (e.g. InvalidPathException or IOException)
                }
            }
        }

        if (displayName != null && !displayName.isBlank()) {
            return Framework.PLAIN_JAVA;
        }
        return Framework.UNKNOWN;
    }
}
