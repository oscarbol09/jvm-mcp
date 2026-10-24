package dev.jvmmcp;

import com.sun.net.httpserver.HttpServer;
import dev.jvmmcp.core.spring.ActuatorAuth;
import dev.jvmmcp.core.spring.ActuatorClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActuatorCommandTest {

    private static HttpServer server;
    private static String serverBaseUrl;

    @BeforeAll
    static void setUpServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] resp = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/actuator/metrics", exchange -> {
            byte[] resp = "{\"names\":[\"jvm.memory.used\",\"jvm.threads.live\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/actuator/metrics/jvm.memory.used", exchange -> {
            byte[] resp = "{\"name\":\"jvm.memory.used\",\"measurements\":[{\"value\":100}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/actuator/metrics/jvm.threads.live", exchange -> {
            byte[] resp = "{\"name\":\"jvm.threads.live\",\"measurements\":[{\"value\":10}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/actuator/startup", exchange -> {
            byte[] resp = "{\"springBootVersion\":\"3.3.0\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/actuator/info", exchange -> {
            byte[] resp = "{\"app\":{\"version\":\"1.0\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        serverBaseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void tearDownServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("actuator command with --help should display help options and return 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("actuator", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("actuator command with missing required options should return usage error code 2")
    void shouldFailOnMissingRequiredOptions() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("actuator");

        assertThat(exitCode).isEqualTo(2);
    }

    @Test
    @DisplayName("actuator command should query health successfully")
    void shouldQueryHealth() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "health");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("\"status\":\"UP\"");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command should default to health when endpoint is omitted")
    void shouldDefaultToHealthWhenEndpointOmitted() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl);

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("\"status\":\"UP\"");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command should query metrics successfully")
    void shouldQueryMetrics() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "metrics");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("jvm.memory.used");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command should query single metric successfully")
    void shouldQuerySingleMetric() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "metrics/jvm.memory.used");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("\"name\":\"jvm.memory.used\"");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command should query batch metrics successfully")
    void shouldQueryBatchMetrics() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "metrics/jvm.memory.used,jvm.threads.live");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("jvm.memory.used");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command should query custom endpoint successfully")
    void shouldQueryCustomEndpoint() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "actuator/info");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("\"version\":\"1.0\"");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command warns on credentials over unencrypted HTTP for non-loopback host")
    void shouldWarnOnCredentialsOverHttp() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            ActuatorClient mockClient = mock(ActuatorClient.class);
            when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

            ActuatorCommand command = new ActuatorCommand((url, auth) -> mockClient, key -> null);
            new CommandLine(command).execute("--url", "http://example.internal:8080", "--token", "secret", "health");

            assertThat(err.toString()).contains("WARNING: Sending Actuator credentials over unencrypted HTTP.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command fails on invalid auth combination")
    void shouldFailOnInvalidAuth() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "--user", "admin", "health");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Username and password must be provided together");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command should query startup successfully")
    void shouldQueryStartup() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", serverBaseUrl, "startup");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("springBootVersion");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("actuator command against unreachable endpoint should return error code 1")
    void shouldFailOnUnreachableEndpoint() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("actuator", "--url", "http://localhost:59999", "health");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Error inspecting Actuator");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command falls back to ACTUATOR_TOKEN from environment")
    void shouldFallbackToActuatorTokenFromEnvironment() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of("ACTUATOR_TOKEN", "env_bearer_token");
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "health");

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().bearerToken()).isEqualTo("env_bearer_token");
        assertThat(capturedAuth.get().user()).isNull();
        assertThat(capturedAuth.get().password()).isNull();
    }

    @Test
    @DisplayName("actuator command falls back to ACTUATOR_USER and ACTUATOR_PASSWORD from environment")
    void shouldFallbackToActuatorBasicAuthFromEnvironment() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of("ACTUATOR_USER", "env_user", "ACTUATOR_PASSWORD", "env_pass");
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "health");

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().user()).isEqualTo("env_user");
        assertThat(capturedAuth.get().password()).isEqualTo("env_pass");
        assertThat(capturedAuth.get().bearerToken()).isNull();
    }

    @Test
    @DisplayName("actuator command explicit CLI token overrides environment basic auth and token")
    void shouldPreferCliTokenOverEnvironmentCredentials() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of(
            "ACTUATOR_USER", "env_user",
            "ACTUATOR_PASSWORD", "env_pass",
            "ACTUATOR_TOKEN", "env_token"
        );
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "--token", "cli_token", "health");

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().bearerToken()).isEqualTo("cli_token");
        assertThat(capturedAuth.get().user()).isNull();
        assertThat(capturedAuth.get().password()).isNull();
    }

    @Test
    @DisplayName("actuator command explicit CLI basic auth overrides environment token")
    void shouldPreferCliBasicAuthOverEnvironmentToken() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of("ACTUATOR_TOKEN", "env_token");
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "http://localhost:8080",
            "--user", "cli_user",
            "--password", "cli_pass",
            "health"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().user()).isEqualTo("cli_user");
        assertThat(capturedAuth.get().password()).isEqualTo("cli_pass");
        assertThat(capturedAuth.get().bearerToken()).isNull();
    }

    @Test
    @DisplayName("actuator command falls back to ACTUATOR_PASSWORD when only user provided on CLI")
    void shouldFallbackPasswordFromEnvironmentWhenOnlyUserProvidedOnCli() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of("ACTUATOR_PASSWORD", "env_pass");
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "http://localhost:8080",
            "--user", "cli_user",
            "health"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().user()).isEqualTo("cli_user");
        assertThat(capturedAuth.get().password()).isEqualTo("env_pass");
        assertThat(capturedAuth.get().bearerToken()).isNull();
    }

    @Test
    @DisplayName("actuator command falls back to ACTUATOR_USER when only password provided on CLI")
    void shouldFallbackUserFromEnvironmentWhenOnlyPasswordProvidedOnCli() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of("ACTUATOR_USER", "env_user");
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();

        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "http://localhost:8080",
            "--password", "cli_pass",
            "health"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().user()).isEqualTo("env_user");
        assertThat(capturedAuth.get().password()).isEqualTo("cli_pass");
        assertThat(capturedAuth.get().bearerToken()).isNull();
    }

    @Test
    @DisplayName("actuator command fails when both token and basic auth are provided in environment")
    void shouldFailWhenBothTokenAndBasicAuthInEnvironment() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Map<String, String> env = Map.of(
                "ACTUATOR_TOKEN", "env_token",
                "ACTUATOR_USER", "env_user",
                "ACTUATOR_PASSWORD", "env_pass"
            );

            ActuatorCommand command = new ActuatorCommand((url, auth) -> mock(ActuatorClient.class), env::get);
            int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "health");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Use either bearer token or username/password credentials, not both.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command fails when only ACTUATOR_USER is provided in environment")
    void shouldFailWhenOnlyActuatorUserProvidedInEnvironment() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Map<String, String> env = Map.of("ACTUATOR_USER", "env_user");

            ActuatorCommand command = new ActuatorCommand((url, auth) -> mock(ActuatorClient.class), env::get);
            int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "health");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Username and password must be provided together.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command fails when only ACTUATOR_PASSWORD is provided in environment")
    void shouldFailWhenOnlyActuatorPasswordProvidedInEnvironment() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Map<String, String> env = Map.of("ACTUATOR_PASSWORD", "env_pass");

            ActuatorCommand command = new ActuatorCommand((url, auth) -> mock(ActuatorClient.class), env::get);
            int exitCode = new CommandLine(command).execute("--url", "http://localhost:8080", "health");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Username and password must be provided together.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command accepts --actuator-user and --actuator-password as valid flag aliases")
    void shouldAcceptActuatorAuthFlagAliases() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();
        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, key -> null);

        int exitCode = new CommandLine(command).execute(
            "--url", "http://127.0.0.1:8080",
            "--actuator-user", "alias_user",
            "--actuator-password", "alias_pass",
            "health"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().user()).isEqualTo("alias_user");
        assertThat(capturedAuth.get().password()).isEqualTo("alias_pass");
    }

    @Test
    @DisplayName("actuator command does not print unencrypted HTTP warning for loopback IP addresses")
    void shouldNotWarnForLoopbackIpAddresses() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            ActuatorClient mockClient = mock(ActuatorClient.class);
            when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

            ActuatorCommand command = new ActuatorCommand((url, auth) -> mockClient, key -> null);
            int exitCode = new CommandLine(command).execute(
                "--url", "http://127.0.0.1:8080",
                "--token", "secret-token",
                "health"
            );

            assertThat(exitCode).isZero();
            assertThat(err.toString()).doesNotContain("WARNING: Sending Actuator credentials over unencrypted HTTP.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("actuator command uses Bearer auth and ignores CLI basic auth when both are passed on CLI")
    void shouldPreferCliTokenWhenBothTokenAndBasicAuthPassedOnCli() {
        ActuatorClient mockClient = mock(ActuatorClient.class);
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env = Map.of(
            "ACTUATOR_TOKEN", "env_token",
            "ACTUATOR_USER", "env_user",
            "ACTUATOR_PASSWORD", "env_pass"
        );
        AtomicReference<ActuatorAuth> capturedAuth = new AtomicReference<>();
        ActuatorCommand command = new ActuatorCommand((url, auth) -> {
            capturedAuth.set(auth);
            return mockClient;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "http://localhost:8080",
            "--token", "cli_token",
            "--user", "cli_user",
            "--password", "cli_pass",
            "health"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().bearerToken()).isEqualTo("cli_token");
        assertThat(capturedAuth.get().user()).isNull();
        assertThat(capturedAuth.get().password()).isNull();
    }
}
