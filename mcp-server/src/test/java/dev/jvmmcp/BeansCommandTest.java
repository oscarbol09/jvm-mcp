package dev.jvmmcp;

import com.sun.net.httpserver.HttpServer;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeansCommandTest {

    private static HttpServer server;
    private static String serverBaseUrl;

    @Mock
    private JvmAttachService mockAttachService;

    private static final String SAMPLE_ACTUATOR_JSON = """
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
        server.createContext("/actuator/beans", exchange -> {
            byte[] responseBytes = SAMPLE_ACTUATOR_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
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
        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl,
            "--actuator-user", "admin", "--actuator-password", "pw", "--actuator-token", "tok");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command should reject a username without a password")
    void shouldRejectUserWithoutPassword() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--actuator-user", "admin");

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
            assertThat(output).contains("Total Beans Matched: 2");
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

    @Test
    @DisplayName("beans command with filter matching no beans should report empty result")
    void shouldHandleEmptyBeansMatch() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--filter", "*NonExistent*");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("No beans matched the specified criteria");
        } finally {
            System.setOut(originalOut);
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
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "orderService");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("BEAN DETAIL: orderService");
            assertThat(output).contains("Type        : dev.jvmmcp.samples.OrderServiceImplementation");
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
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "paymentGateway");

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
    @DisplayName("beans command with --detail for non-existent bean should return error exit code 1")
    void shouldFailWhenDetailBeanNotFound() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "unknownBean");

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
            int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl,
                "--actuator-token", "test-token", "--insecure");

            assertThat(exitCode).isZero();
            String errOutput = err.toString();
            assertThat(errOutput).contains("Warning: --insecure disables TLS");
            assertThat(errOutput).contains("Warning: credentials are sent over plain HTTP");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("beans command on attached PID should execute local inspection")
    void shouldInspectAttachedProcessBeans() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        BeansCommand command = new BeansCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("SPRING BEANS INSPECTION");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("beans command should return error when attach fails")
    void shouldHandleAttachFailureGracefully() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.processNotFound(String.valueOf(targetPid)));

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
        when(mockAttachService.attach(String.valueOf(targetPid))).thenThrow(new RuntimeException("JMX error"));

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
    @DisplayName("Default constructor should initialize properly")
    void shouldInitializeWithDefaultConstructor() {
        BeansCommand command = new BeansCommand();
        assertThat(command.attachService).isNotNull();
    }
}
