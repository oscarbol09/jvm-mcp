package dev.jvmmcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BeansCommandTest {

    private static HttpServer server;
    private static String serverBaseUrl;

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
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Inspects live Spring Boot ApplicationContext");
        assertThat(out.toString()).contains("--filter");
        assertThat(out.toString()).contains("--detail");
        assertThat(out.toString()).contains("--actuator");
        assertThat(out.toString()).contains("--actuator-user");
        assertThat(out.toString()).contains("--actuator-password");
        assertThat(out.toString()).contains("--actuator-token");
        assertThat(out.toString()).contains("--insecure");
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
    @DisplayName("beans command with non-existent PID should return error exit code")
    void shouldFailGracefullyOnNonExistentPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "999999999");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command against Actuator URL should list beans overview")
    void shouldListBeansOverviewFromActuatorUrl() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl);

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("SPRING BEANS INSPECTION");
        assertThat(output).contains("Total Beans Matched: 2");
        assertThat(output).contains("orderService");
        assertThat(output).contains("paymentGateway");
    }

    @Test
    @DisplayName("beans command with --filter should match specified glob pattern")
    void shouldFilterBeansWithGlobPattern() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--filter", "*Order*");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("Total Beans Matched: 1");
        assertThat(output).contains("orderService");
    }

    @Test
    @DisplayName("beans command with filter matching no beans should report empty result")
    void shouldHandleEmptyBeansMatch() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--filter", "*NonExistent*");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("No beans matched the specified criteria");
    }

    @Test
    @DisplayName("beans command with --detail on existing bean should display deep inspection")
    void shouldInspectBeanDetail() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

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
    }

    @Test
    @DisplayName("beans command with --detail on bean with no dependencies should show (None)")
    void shouldInspectBeanDetailWithoutDependencies() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl, "--detail", "paymentGateway");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("BEAN DETAIL: paymentGateway");
        assertThat(output).contains("DIRECT DEPENDENCIES (0)");
        assertThat(output).contains("(None)");
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
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("beans", "--actuator", serverBaseUrl,
            "--actuator-token", "test-token", "--insecure");

        assertThat(exitCode).isZero();
        String errOutput = err.toString();
        assertThat(errOutput).contains("Warning: --insecure disables TLS");
        assertThat(errOutput).contains("Warning: credentials are sent over plain HTTP");
    }

    @Test
    @DisplayName("beans aliases bean, spring-beans, sb should execute command")
    void shouldSupportAliases() {
        CommandLine cmd = new CommandLine(new JvmMcp());

        int beanExit = cmd.execute("bean", "--actuator", serverBaseUrl);
        int sbExit = cmd.execute("sb", "--actuator", serverBaseUrl);
        int springBeansExit = cmd.execute("spring-beans", "--actuator", serverBaseUrl);

        assertThat(beanExit).isZero();
        assertThat(sbExit).isZero();
        assertThat(springBeansExit).isZero();
    }

    @Test
    @DisplayName("beans command on current PID should execute inspection")
    void shouldInspectCurrentProcessBeans() {
        long currentPid = ProcessHandle.current().pid();
        CommandLine cmd = new CommandLine(new JvmMcp());

        int exitCode = cmd.execute("beans", String.valueOf(currentPid));
        // Current JVM runner does not have Spring LiveBeansView MBean registered, so it gracefully reports 0 beans
        assertThat(exitCode).isZero();
    }
}
