package dev.jvmmcp.core.attach;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Handles extraction of the bundled diagnostic agent JAR into the user cache directory with checksum verification.
 * Employs FileChannel locks and atomic moves to prevent concurrency issues across multiple JVM processes.
 */
public class AgentExtractor {

    private static final String AGENT_RESOURCE_PATH = "/agent/jvm-mcp-agent.jar";

    public Path extractAgentJar() throws IOException {
        return extractAgentJar(resolveCacheDir(), AGENT_RESOURCE_PATH);
    }

    Path extractAgentJar(Path cacheDir, String resourcePath) throws IOException {
        Files.createDirectories(cacheDir);
        Path targetPath = cacheDir.resolve("jvm-mcp-agent.jar");
        Path lockPath = cacheDir.resolve("jvm-mcp-agent.lock");

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                if (Files.exists(targetPath)) {
                    return targetPath;
                }
                throw new IOException("Embedded agent resource not found at " + resourcePath);
            }

            byte[] resourceBytes = in.readAllBytes();
            String resourceChecksum = calculateChecksum(resourceBytes);

            // Fast path check
            if (Files.exists(targetPath) && !Files.isSymbolicLink(targetPath)) {
                String existingChecksum = calculateChecksum(Files.readAllBytes(targetPath));
                if (resourceChecksum.equals(existingChecksum)) {
                    return targetPath;
                }
            }

            // Acquire OS-level lock to prevent concurrent extraction
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {

                // Double check after lock
                if (Files.exists(targetPath)) {
                    if (Files.isSymbolicLink(targetPath)) {
                        throw new IOException("Security Error: Target path is a symbolic link, preventing potential arbitrary file overwrite: " + targetPath);
                    }
                    String existingChecksum = calculateChecksum(Files.readAllBytes(targetPath));
                    if (resourceChecksum.equals(existingChecksum)) {
                        return targetPath;
                    }
                }

                Path tempJar = Files.createTempFile(cacheDir, "jvm-mcp-agent-", ".tmp");
                try {
                    Files.write(tempJar, resourceBytes);
                    Files.move(tempJar, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tempJar); // Cleanup in case move fails
                }
                return targetPath;
            }
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
