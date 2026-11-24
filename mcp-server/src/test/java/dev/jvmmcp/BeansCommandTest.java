package dev.jvmmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class BeansCommandTest {

    private static HttpServer server;
    private static String serverBaseUrl;

    @Mock private JvmAttachService mockAttachService;

    private static final String SAMPLE_ACTUATOR_JSON =
            """
        {
          "contexts": {
            "application": {
              "beans": {
                "orderService": {
                  "aliases": ["orderManager", "orderSvc"],
                  "scope": "singleton",
                  "type": "dev.jvmmcp.samples.OrderServiceImplementation",
                  "resource": "URL [file:/app/classes/dev/jvmmcp/samples/OrderServiceImplementation.class]",
                  "dependencies": ["orderRepository", "paymentGateway"]
                },
                "paymentGateway": {
                  "aliases": [],
                  "scope": "singleton",
                  "type": "dev.jvmmcp.samples.PaymentGatewayVeryLongClassNameForTruncationTesting",
                  "resource": null,
                  "dependencies": []
                },
                "shortBean": {
                  "aliases": [],
                  "scope": "prototype",
                  "type": "String",
                  "resource": null,
                  "dependencies": []
                },
                "nullTypeBean": {
                  "aliases": [],
                  "scope": "prototype",
                  "type": null,
                  "resource": null,
                  "dependencies": []
                }
              },
              "parentId": null
            }
          }
        }
        """;

    @BeforeAll
    static void startHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/actuator/beans",
                exchange -> {
                    byte[] responseBytes = SAMPLE_ACTUATOR_JSON.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                });
        server.start();
        serverBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopHttpServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("beans command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("beans command should reject Basic and Bearer credentials used together")
    void shouldRejectBasicAndBearerTogether() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode =
                cmd.execute(
                        "beans",
                        "--actuator",
                        serverBaseUrl,
                        "--actuator-user",
                        "admin",
                        "--actuator-password",
                        "pw",
                        "--actuator-token",
                        "tok");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command should reject a username without a password")
    void shouldRejectUserWithoutPassword() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode =
                cmd.execute("beans", "--actuator", serverBaseUrl, "--actuator-user", "admin");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command with missing PID and no actuator URL should return error exit code")
    void shouldFailOnMissingPidAndActuator() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command against Actuator URL should list beans overview")
    void shouldListBeansOverviewFromActuatorUrl() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl);

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("SPRING BEANS INSPECTION");
            assertThat(output).contains("Total Beans Matched: 4");
            assertThat(output).contains("orderService");
            assertThat(output).contains("paymentGateway");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command with --filter should match specified glob pattern")
    void shouldFilterBeansWithGlobPattern() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--filter", "*Order*");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("Total Beans Matched: 1");
            assertThat(output).contains("orderService");
        } finally {
            System.setOut(originalOut);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("beans command with filter matching no beans should report empty success")
    void shouldHandleEmptyBeansMatch(boolean json) {
        BeansCommand command = new BeansCommand(mockAttachService, key -> null);
        command.actuatorUrl = serverBaseUrl;
        command.filter = "*NonExistent*";
        command.json = json;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            assertThat(command.call()).isZero();
            assertThat(err.toString()).isEmpty();
            if (json) {
                Map<String, Object> report =
                        dev.jvmmcp.core.util.SimpleJson.parseObject(out.toString());
                assertThat(((Number) report.get("totalBeans")).intValue()).isZero();
                assertThat(report.get("discoverySource").toString()).startsWith("ACTUATOR_HTTP");
            } else {
                assertThat(out.toString())
                        .contains(
                                "Total Beans Matched: 0",
                                "No beans matched the specified criteria");
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("beans command with --detail on existing bean should display deep inspection")
    void shouldInspectBeanDetail() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode =
                    cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "orderService");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("BEAN DETAIL: orderService");
            assertThat(output)
                    .contains("Type        : dev.jvmmcp.samples.OrderServiceImplementation");
            assertThat(output).contains("Scope       : singleton");
            assertThat(output).contains("Aliases     : orderManager, orderSvc");
            assertThat(output).contains("DIRECT DEPENDENCIES (2)");
            assertThat(output).contains("-> orderRepository");
            assertThat(output).contains("-> paymentGateway");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command with --detail on bean with no dependencies should show (None)")
    void shouldInspectBeanDetailWithoutDependencies() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode =
                    cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "paymentGateway");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("BEAN DETAIL: paymentGateway");
            assertThat(output).contains("DIRECT DEPENDENCIES (0)");
            assertThat(output).contains("(None)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName(
            "beans command with --detail for non-existent bean should return error exit code 1")
    void shouldFailWhenDetailBeanNotFound() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "unknownBean");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "beans command with blank detailBeanName or actuatorUrl should not trigger those branches")
    void shouldHandleBlankStringsGracefully() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", "   ", "--detail", "   ");

        // Fails because actuator is blank so it falls back to PID 0 which is invalid
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command with --insecure and credentials warnings")
    void shouldEmitWarningsForInsecureAndPlainHttp() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode =
                    cmd.execute(
                            "beans",
                            "--actuator",
                            serverBaseUrl,
                            "--actuator-token",
                            "test-token",
                            "--insecure");

            assertThat(exitCode).isZero();
            String errOutput = err.toString();
            assertThat(errOutput).contains("Warning: --insecure disables TLS");
            assertThat(errOutput).contains("Warning: credentials are sent over plain HTTP");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("beans command with https actuator skips plain HTTP warning")
    void shouldSkipPlainHttpWarningForHttps() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            // It will fail with exit code 1 because https://1.1.1.1:9999 won't be open,
            // but that's fine, we just want to cover the warning branch condition correctly.
            int exitCode =
                    cmd.execute(
                            "beans",
                            "--actuator",
                            "https://1.1.1.1:9999",
                            "--actuator-token",
                            "test-token",
                            "--insecure");

            assertThat(exitCode).isEqualTo(1);
            String errOutput = err.toString();
            assertThat(errOutput).contains("Warning: --insecure disables TLS");
            assertThat(errOutput).doesNotContain("Warning: credentials are sent over plain HTTP");
        } finally {
            System.setErr(originalErr);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @DisplayName("beans command should fail when no discovery source is available")
    void shouldFailWhenBeansDiscoveryIsUnavailable(boolean json, boolean detail) {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        BeansCommand command = new BeansCommand(mockAttachService, key -> null);
        command.pid = targetPid;
        command.json = json;
        command.detailBeanName = detail ? "orderService" : null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            assertThat(command.call()).isEqualTo(1);
            assertThat(out.toString()).isEmpty();
            assertThat(err.toString())
                    .contains(
                            "Unable to inspect Spring beans",
                            "NOT_AVAILABLE",
                            "JMX MBean not registered")
                    .contains("Hint:", "Actuator", "spring.jmx.enabled=true")
                    .doesNotContain("not found in active contexts");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("beans command should explain unreachable Actuator failures")
    void shouldExplainUnreachableActuatorFailure(boolean json) {
        BeansCommand command = new BeansCommand(mockAttachService, key -> null);
        command.actuatorUrl = serverBaseUrl + "/unavailable";
        command.json = json;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            assertThat(command.call()).isEqualTo(1);
            assertThat(out.toString()).isEmpty();
            assertThat(err.toString())
                    .contains(
                            "Unable to inspect Spring beans",
                            "ACTUATOR_UNREACHABLE",
                            command.actuatorUrl)
                    .contains("Hint:", "Actuator", "spring.jmx.enabled=true");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Stream<Arguments> rejectedActuatorUrls() {
        return Stream.of(
                        "http://169.254.169.254/latest/meta-data",
                        serverBaseUrl.replace("http://", "http://user:secret-value@"),
                        serverBaseUrl + "?token=secret-value",
                        serverBaseUrl + "/bad path?token=secret-value")
                .flatMap(url -> Stream.of(Arguments.of(false, url), Arguments.of(true, url)));
    }

    @ParameterizedTest
    @MethodSource("rejectedActuatorUrls")
    @DisplayName(
            "beans CLI reports URL rejection without secrets, a stack trace, or success output")
    void shouldExplainRejectedActuatorUrl(boolean json, String url) {
        BeansCommand command = new BeansCommand(mockAttachService, key -> null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            String[] args =
                    json
                            ? new String[] {"--actuator", url, "--json"}
                            : new String[] {"--actuator", url};
            assertThat(new CommandLine(command).execute(args)).isEqualTo(1);
            assertThat(out.toString()).isEmpty();
            assertThat(err.toString())
                    .contains("[jvm-mcp] Error:", "SSRF Protection:")
                    .doesNotContain(
                            "secret-value",
                            "ACTUATOR_UNREACHABLE",
                            "Hint:",
                            "IllegalArgumentException",
                            "\tat ");
            org.mockito.Mockito.verifyNoInteractions(mockAttachService);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("beans command uses Actuator before opening JMX and detaches the VM")
    void shouldUseActuatorBeforeJmxForAttachedProcess() throws Exception {
        long targetPid = ProcessHandle.current().pid() + 1;
        com.sun.tools.attach.VirtualMachine mockVm =
                org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachine.class);
        java.util.Properties systemProperties = new java.util.Properties();
        systemProperties.setProperty(
                "server.port", serverBaseUrl.substring(serverBaseUrl.lastIndexOf(':') + 1));
        when(mockVm.getSystemProperties()).thenReturn(systemProperties);
        when(mockVm.getAgentProperties()).thenReturn(new java.util.Properties());
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.success(String.valueOf(targetPid), mockVm));

        BeansCommand command = new BeansCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("ACTUATOR_HTTP", "orderService");
            org.mockito.Mockito.verify(mockAttachService).detach(mockVm);
            org.mockito.Mockito.verify(mockVm, org.mockito.Mockito.never())
                    .startLocalManagementAgent();
            org.mockito.Mockito.verify(mockVm, org.mockito.Mockito.never())
                    .loadAgent(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString());
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command on attached remote PID should attempt connect")
    void shouldAttemptRemoteConnectOnRemoteVm() throws Exception {
        long targetPid = 100L;
        com.sun.tools.attach.VirtualMachine mockVm =
                org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachine.class);
        java.util.Properties props = new java.util.Properties();
        props.setProperty(
                "com.sun.management.jmxremote.localConnectorAddress",
                "service:jmx:rmi:///jndi/rmi://localhost:9000/jmxrmi");
        when(mockVm.getAgentProperties()).thenReturn(props);
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.success(String.valueOf(targetPid), mockVm));

        BeansCommand command = new BeansCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();
            assertThat(exitCode).isEqualTo(1);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command should return error when attach fails")
    void shouldHandleAttachFailureGracefully() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.processNotFound(String.valueOf(targetPid)));

        BeansCommand command = new BeansCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("not found");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("beans command should handle unexpected inspection exception")
    void shouldHandleInspectionExceptionGracefully() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenThrow(new RuntimeException("JMX error"));

        BeansCommand command = new BeansCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("JMX error");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName(
            "beans command with --json should output parseable JSON report without ASCII decorations")
    void shouldOutputJsonReport() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--json");

            assertThat(exitCode).isZero();
            String output = out.toString().trim();
            assertThat(output).doesNotContain("=");
            assertThat(output).doesNotContain("SPRING BEANS INSPECTION");

            Map<String, Object> parsed = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            assertThat(parsed).containsKey("totalBeans");
            assertThat(parsed).containsKey("contexts");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command with --json --detail should output parseable JSON bean detail")
    void shouldOutputJsonBeanDetail() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode =
                    cmd.execute(
                            "beans",
                            "--actuator",
                            serverBaseUrl,
                            "--json",
                            "--detail",
                            "orderService");

            assertThat(exitCode).isZero();
            String output = out.toString().trim();
            assertThat(output).doesNotContain("BEAN DETAIL");

            Map<String, Object> parsed = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            assertThat(parsed).containsEntry("contextId", "application");
            assertThat(parsed).containsEntry("matchedBy", "EXACT");

            @SuppressWarnings("unchecked")
            Map<String, Object> bean = (Map<String, Object>) parsed.get("bean");

            assertThat(bean).containsEntry("name", "orderService");
            assertThat(bean).containsEntry("type", "dev.jvmmcp.samples.OrderServiceImplementation");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName(
            "beans command with --json --detail for non-existent bean should return exit code 1")
    void shouldFailJsonDetailWhenBeanNotFound() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode =
                cmd.execute(
                        "beans", "--actuator", serverBaseUrl, "--json", "--detail", "unknownBean");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("Default constructor should initialize properly")
    void shouldInitializeWithDefaultConstructor() {
        BeansCommand command = new BeansCommand();
        assertThat(command).isNotNull();
    }

    @Test
    @DisplayName("beans command reads Actuator credentials from environment variables")
    void shouldReadAuthFromEnvironmentVariables() {
        Map<String, String> env =
                Map.of(
                        "ACTUATOR_USER", "env_user",
                        "ACTUATOR_PASSWORD", "env_pass");
        BeansCommand cmd = new BeansCommand(new JvmAttachService(), env::get);
        cmd.actuatorUrl = serverBaseUrl;
        int exitCode = cmd.call();
        assertThat(exitCode).isZero();
    }
}
