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
    @DisplayName("resolveCacheDir returns valid non-empty path across OS environments")
    void shouldResolveValidCacheDir() {
        Path cacheDir = agentExtractor.resolveCacheDir();

        assertThat(cacheDir).isNotNull();
        assertThat(cacheDir.toString()).contains("jvm-mcp");
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
