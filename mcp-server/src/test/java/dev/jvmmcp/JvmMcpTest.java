package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMcpTest {

    @Test
    @DisplayName("Root command should display help usage and return exit code 0")
    void shouldDisplayHelpUsage() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Live JVM inspection via Model Context Protocol");
        assertThat(out.toString()).contains("Commands:");
        assertThat(out.toString()).contains("serve");
        assertThat(out.toString()).contains("list");
        assertThat(out.toString()).contains("memory");
        assertThat(out.toString()).contains("threads");
        assertThat(out.toString()).contains("beans");
    }

    @Test
    @DisplayName("Root command should display version and return exit code 0")
    void shouldDisplayVersion() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains(JvmMcp.getVersion());
        assertThat(out.toString()).contains("1.1.1");
    }

    @Test
    @DisplayName("Root command should display version with -V and return exit code 0")
    void shouldDisplayVersionWithShortOption() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("-V");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains(JvmMcp.getVersion());
    }

    @Test
    @DisplayName("Version provider should return version array")
    void shouldProvideVersionFromVersionProvider() {
        JvmMcp.VersionProvider provider = new JvmMcp.VersionProvider();
        assertThat(provider.getVersion()).containsExactly(JvmMcp.getVersion());

        JvmMcp.ManifestVersionProvider manifestProvider = new JvmMcp.ManifestVersionProvider();
        assertThat(manifestProvider.getVersion()).containsExactly(JvmMcp.getVersion());
    }

    @Test
    @DisplayName("resolveVersion should prefer package implementation version when present")
    void shouldPreferPackageImplementationVersion() {
        String resolved = JvmMcp.resolveVersion("2.0.0", null);
        assertThat(resolved).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("resolveVersion should read from properties stream when package version is null")
    void shouldReadFromPropertiesStream() {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream("version=1.2.3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String resolved = JvmMcp.resolveVersion(null, in);
        assertThat(resolved).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("resolveVersion should fall back to default when properties contain placeholder or are empty")
    void shouldFallbackToDefaultWhenPlaceholderOrEmpty() {
        java.io.ByteArrayInputStream inWithPlaceholder = new java.io.ByteArrayInputStream("version=${project.version}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(JvmMcp.resolveVersion(null, inWithPlaceholder)).isEqualTo("1.1.1");

        java.io.ByteArrayInputStream emptyIn = new java.io.ByteArrayInputStream("".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(JvmMcp.resolveVersion(null, emptyIn)).isEqualTo("1.1.1");

        assertThat(JvmMcp.resolveVersion(null, null)).isEqualTo("1.1.1");
    }

    @Test
    @DisplayName("Calling root command directly displays usage and returns 0")
    void shouldCallRootCommandDirectly() {
        JvmMcp root = new JvmMcp();
        Integer exitCode = root.call();
        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("Invoking with invalid subcommand should return error exit code 2")
    void shouldReturnErrorOnInvalidSubcommand() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("non-existent-subcommand");

        assertThat(exitCode).isNotZero();
    }
}
