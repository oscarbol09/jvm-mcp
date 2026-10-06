package dev.jvmmcp.core.attach;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExtractorTest {

    private AgentExtractor agentExtractor;

    @BeforeEach
    void setUp() {
        agentExtractor = new AgentExtractor();
    }

    @Test
    @DisplayName("extractAgentJar extracts embedded agent jar into cache directory and verifies checksum")
    void shouldExtractAgentJarSuccessfully(@TempDir Path tempCache) throws IOException {
        AgentExtractor customExtractor = new AgentExtractor() {
            @Override
            public Path resolveCacheDir() {
                return tempCache;
            }
        };

        Path extracted = customExtractor.extractAgentJar();

        assertThat(extracted).isNotNull();
        assertThat(Files.exists(extracted)).isTrue();
        assertThat(extracted.getFileName().toString()).isEqualTo("jvm-mcp-agent.jar");
        assertThat(Files.readAllBytes(extracted)).isNotEmpty();

        // Calling extraction again with matching checksum should reuse existing file
        Path reused = customExtractor.extractAgentJar();
        assertThat(reused).isEqualTo(extracted);
    }

    @Test
    @DisplayName("extractAgentJar overwrites target jar when cached file checksum differs")
    void shouldOverwriteWhenCachedChecksumDiffers(@TempDir Path tempCache) throws IOException {
        AgentExtractor customExtractor = new AgentExtractor() {
            @Override
            public Path resolveCacheDir() {
                return tempCache;
            }
        };

        Files.createDirectories(tempCache);
        Path targetFile = tempCache.resolve("jvm-mcp-agent.jar");
        Files.writeString(targetFile, "corrupted-or-outdated-agent-bytes");

        Path extracted = customExtractor.extractAgentJar();

        assertThat(extracted).isEqualTo(targetFile);
        assertThat(Files.readString(extracted)).isNotEqualTo("corrupted-or-outdated-agent-bytes");
    }

    @Test
    @DisplayName("extractAgentJar handles missing resource stream gracefully")
    void shouldHandleMissingResourceStream(@TempDir Path tempCache) throws IOException {
        // When resource is missing but target file already exists in cache
        Path existingTarget = tempCache.resolve("jvm-mcp-agent.jar");
        Files.writeString(existingTarget, "pre-existing-agent");

        Path returned = agentExtractor.extractAgentJar(tempCache, "/nonexistent/path.jar");
        assertThat(returned).isEqualTo(existingTarget);

        // When resource is missing and target file does not exist
        Files.delete(existingTarget);
        assertThatThrownBy(() -> agentExtractor.extractAgentJar(tempCache, "/nonexistent/path.jar"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Embedded agent resource not found");
    }

    @Test
    @DisplayName("resolveCacheDir resolves paths accurately across Windows and Unix platforms")
    void shouldResolveCacheDirAcrossPlatforms() {
        Path winWithAppData = agentExtractor.resolveCacheDir("Windows 11", "C:\\AppData", "C:\\Users\\test");
        assertThat(winWithAppData).isEqualTo(Path.of("C:\\AppData", "jvm-mcp"));

        Path winWithoutAppData = agentExtractor.resolveCacheDir("Windows 10", null, "C:\\Users\\test");
        assertThat(winWithoutAppData).isEqualTo(Path.of("C:\\Users\\test", ".jvm-mcp"));

        Path unixPath = agentExtractor.resolveCacheDir("Linux", null, "/home/test");
        assertThat(unixPath).isEqualTo(Path.of("/home/test", ".cache", "jvm-mcp"));

        Path current = agentExtractor.resolveCacheDir();
        assertThat(current).isNotNull();
        assertThat(current.toString()).contains("jvm-mcp");
    }

    @Test
    @DisplayName("calculateChecksum produces accurate SHA-256 hex string")
    void shouldCalculateDeterministicChecksum() {
        byte[] empty = new byte[0];
        String emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        assertThat(agentExtractor.calculateChecksum(empty)).isEqualTo(emptySha256);

        byte[] sampleData = "diagnostic-agent-sample-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum1 = agentExtractor.calculateChecksum(sampleData);
        String checksum2 = agentExtractor.calculateChecksum(sampleData);

        assertThat(checksum1)
            .isNotNull()
            .isEqualTo(checksum2)
            .hasSize(64);
    }
}
