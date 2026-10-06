package dev.jvmmcp.core.attach;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Handles extraction of the bundled diagnostic agent JAR into the user cache directory with checksum verification.
 */
public class AgentExtractor {

    private static final String AGENT_RESOURCE_PATH = "/agent/jvm-mcp-agent.jar";

    public Path extractAgentJar() throws IOException {
        return extractAgentJar(resolveCacheDir(), AGENT_RESOURCE_PATH);
    }

    Path extractAgentJar(Path cacheDir, String resourcePath) throws IOException {
        Files.createDirectories(cacheDir);
        Path targetPath = cacheDir.resolve("jvm-mcp-agent.jar");

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                if (Files.exists(targetPath)) {
                    return targetPath;
                }
                throw new IOException("Embedded agent resource not found at " + resourcePath);
            }

            byte[] resourceBytes = in.readAllBytes();
            String resourceChecksum = calculateChecksum(resourceBytes);

            if (Files.exists(targetPath)) {
                String existingChecksum = calculateChecksum(Files.readAllBytes(targetPath));
                if (resourceChecksum.equals(existingChecksum)) {
                    return targetPath;
                }
            }

            Files.write(targetPath, resourceBytes);
            return targetPath;
        }
    }

    public Path resolveCacheDir() {
        return resolveCacheDir(System.getProperty("os.name", ""), System.getenv("LOCALAPPDATA"), System.getProperty("user.home", ""));
    }

    Path resolveCacheDir(String osName, String localAppData, String userHome) {
        String os = osName != null ? osName.toLowerCase() : "";
        if (os.contains("win")) {
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "jvm-mcp");
            }
            return Path.of(userHome != null ? userHome : "", ".jvm-mcp");
        }
        return Path.of(userHome != null ? userHome : "", ".cache", "jvm-mcp");
    }

    public String calculateChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing in standard runtime", e);
        }
    }
}
