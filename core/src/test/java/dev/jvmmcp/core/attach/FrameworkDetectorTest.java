package dev.jvmmcp.core.attach;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmmcp.core.model.Framework;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FrameworkDetectorTest {

    @ParameterizedTest(name = "Detect {1} for displayName=''{0}''")
    @CsvSource({
        "org.springframework.boot.loader.JarLauncher, SPRING_BOOT",
        "io.quarkus.runner.GeneratedMain, QUARKUS",
        "io.micronaut.runtime.Micronaut, MICRONAUT",
        "com.example.plain.MainApp, PLAIN_JAVA"
    })
    @DisplayName("Should detect expected framework based on process display name")
    void shouldDetectExpectedFramework(String displayName, Framework expected) {
        Framework result = FrameworkDetector.detect(displayName, "");
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("Should return UNKNOWN for null or empty metadata")
    void shouldReturnUnknownForBlankMetadata() {
        assertThat(FrameworkDetector.detect(null, null)).isEqualTo(Framework.UNKNOWN);
        assertThat(FrameworkDetector.detect("", "")).isEqualTo(Framework.UNKNOWN);
    }

    @Test
    @DisplayName("Should not classify framework names found only in application metadata")
    void shouldIgnoreFrameworkSubstringsOutsideMainClass() {
        assertThat(
                        FrameworkDetector.detect(
                                "com.example.QuarkusMigrationTool -Dspring-boot.enabled=true",
                                "com.example.QuarkusMigrationTool"))
                .isEqualTo(Framework.PLAIN_JAVA);
    }

    @Test
    @DisplayName("Should resolve a relative Spring Boot jar against the target working directory")
    void shouldResolveRelativeJarAgainstTargetWorkingDirectory(@TempDir Path tempDir)
            throws IOException {
        Path app = tempDir.resolve("app.jar");
        try (JarOutputStream ignored =
                new JarOutputStream(Files.newOutputStream(app), manifest())) {
            // The manifest is sufficient for framework detection.
        }

        assertThat(FrameworkDetector.detect("java -jar app.jar", "app.jar", tempDir))
                .isEqualTo(Framework.SPRING_BOOT);
        assertThat(FrameworkDetector.detect("java -jar app.jar", "app.jar", null))
                .isEqualTo(Framework.PLAIN_JAVA);
    }

    @Test
    @DisplayName("Should detect an absolute framework jar without a working directory")
    void shouldDetectAbsoluteJar() throws IOException {
        Path tempDir = Files.createTempDirectory("framework-detector-");
        try {
            Path app = tempDir.resolve("app.jar");
            try (JarOutputStream ignored =
                    new JarOutputStream(Files.newOutputStream(app), manifest())) {
                // The manifest is sufficient for framework detection.
            }

            assertThat(FrameworkDetector.detect(app.toString(), "app.jar", null))
                    .isEqualTo(Framework.SPRING_BOOT);
        } finally {
            Files.deleteIfExists(tempDir.resolve("app.jar"));
            Files.deleteIfExists(tempDir);
        }
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.4.0");
        return manifest;
    }
}
