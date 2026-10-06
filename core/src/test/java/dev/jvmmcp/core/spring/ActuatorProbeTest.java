package dev.jvmmcp.core.spring;

import com.sun.net.httpserver.HttpServer;
import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActuatorProbeTest {

    private static final String BEANS_PAYLOAD = "{\"contexts\":{\"application\":{\"beans\":{\"userService\":{}}}}}";
    private static final String NON_BEANS_PAYLOAD = "<html><body>Welcome</body></html>";

    private HttpServer server;
    private int port;

    @Mock
    private VirtualMachine virtualMachine;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("probe returns empty when VirtualMachine is null")
    void shouldReturnEmptyWhenVmIsNull() {
        ActuatorProbe probe = new ActuatorProbe();
        assertThat(probe.probe(null)).isEmpty();
    }

    @Test
    @DisplayName("probe extracts candidate ports from VM properties and succeeds on active port")
    void shouldProbeActivePortFromVmProperties() throws Exception {
        server.createContext("/actuator/beans", exchange -> {
            byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Properties sysProps = new Properties();
        sysProps.setProperty("management.server.port", String.valueOf(port));
        sysProps.setProperty("server.port", "8080");
        sysProps.setProperty("local.management.port", "-1");
        sysProps.setProperty("local.server.port", "invalid-port");

        Properties agentProps = new Properties();
        agentProps.setProperty("management.server.port", "999999");
        agentProps.setProperty("server.port", "");

        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(agentProps);

        ActuatorProbe probe = new ActuatorProbe();
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isPresent();
        assertThat(result.get().url()).contains(":" + port + "/actuator/beans");
        assertThat(result.get().rawJson()).isEqualTo(BEANS_PAYLOAD);
    }

    @Test
    @DisplayName("probe handles getSystemProperties throwing exception gracefully")
    void shouldHandleVmPropertiesException() throws Exception {
        when(virtualMachine.getSystemProperties()).thenThrow(new IOException("Attach read failure"));

        ActuatorProbe probe = new ActuatorProbe();
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("probe returns empty when target endpoints do not contain beans")
    void shouldReturnEmptyWhenNoBeansPayloadFound() throws Exception {
        server.createContext("/actuator/beans", exchange -> {
            byte[] body = NON_BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Properties sysProps = new Properties();
        sysProps.setProperty("management.server.port", String.valueOf(port));
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(new Properties());

        ActuatorProbe probe = new ActuatorProbe();
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("probePort falls back to /beans when /actuator/beans returns 404")
    void shouldFallbackToCustomBeansPath() {
        server.createContext("/actuator/beans", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });
        server.createContext("/beans", exchange -> {
            byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ActuatorProbe probe = new ActuatorProbe();
        Optional<String> json = probe.probePort(port);

        assertThat(json).isPresent();
        assertThat(json.get()).isEqualTo(BEANS_PAYLOAD);
    }

    @Test
    @DisplayName("fetchFromUrl returns empty for unreachable URL")
    void shouldHandleUnreachableUrlInFetch() {
        ActuatorProbe probe = new ActuatorProbe();
        Optional<String> result = probe.fetchFromUrl("http://127.0.0.1:1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Insecure TLS configuration properly initialises TrustAllManager methods")
    void shouldInitializeInsecureTlsContext() throws Exception {
        ActuatorAuth auth = new ActuatorAuth(null, null, null, true);
        ActuatorProbe probe = new ActuatorProbe(auth);
        assertThat(probe).isNotNull();

        Class<?> trustAllClass = Class.forName("dev.jvmmcp.core.spring.ActuatorProbe$TrustAllManager");
        Constructor<?> ctor = trustAllClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        X509ExtendedTrustManager manager = (X509ExtendedTrustManager) ctor.newInstance();

        manager.checkClientTrusted(new X509Certificate[0], "RSA");
        manager.checkServerTrusted(new X509Certificate[0], "RSA");
        manager.checkClientTrusted(new X509Certificate[0], "RSA", (Socket) null);
        manager.checkServerTrusted(new X509Certificate[0], "RSA", (Socket) null);
        manager.checkClientTrusted(new X509Certificate[0], "RSA", (SSLEngine) null);
        manager.checkServerTrusted(new X509Certificate[0], "RSA", (SSLEngine) null);
        assertThat(manager.getAcceptedIssuers()).isEmpty();
    }

    @Test
    @DisplayName("Custom HttpClient and null ActuatorAuth constructors initialize reliably")
    void shouldSupportAlternativeConstructors() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        ActuatorProbe probe1 = new ActuatorProbe(client);
        ActuatorProbe probe2 = new ActuatorProbe(client, null);
        ActuatorProbe probe3 = new ActuatorProbe((ActuatorAuth) null);

        assertThat(probe1).isNotNull();
        assertThat(probe2).isNotNull();
        assertThat(probe3).isNotNull();
    }

    @Test
    @DisplayName("ActuatorAuth accessors and credential state")
    void shouldVerifyActuatorAuthProperties() {
        ActuatorAuth basic = ActuatorAuth.basic("admin", "pass", true);
        assertThat(basic.hasCredentials()).isTrue();
        assertThat(basic.insecure()).isTrue();

        ActuatorAuth bearer = ActuatorAuth.bearer("jwt-token", true);
        assertThat(bearer.hasCredentials()).isTrue();
        assertThat(bearer.insecure()).isTrue();

        ActuatorAuth none = ActuatorAuth.NONE;
        assertThat(none.hasCredentials()).isFalse();
        assertThat(none.insecure()).isFalse();
    }

    @Test
    @DisplayName("ActuatorAuth handles empty/null strings gracefully")
    void shouldHandleEmptyAuthStrings() {
        ActuatorAuth nullPass = ActuatorAuth.basic("admin", null, false);
        assertThat(nullPass.hasCredentials()).isFalse();

        ActuatorAuth emptyPass = ActuatorAuth.basic("admin", "", false);
        assertThat(emptyPass.hasCredentials()).isFalse();

        ActuatorAuth blankUser = ActuatorAuth.basic("   ", "pass", false);
        assertThat(blankUser.hasCredentials()).isFalse();

        ActuatorAuth blankToken = ActuatorAuth.bearer("   ", false);
        assertThat(blankToken.hasCredentials()).isFalse();
    }

    @Test
    @DisplayName("probe returns empty on 200 but body does not contain beans")
    void shouldReturnEmptyOn200WithoutBeans() throws Exception {
        server.createContext("/actuator/beans", exchange -> {
            byte[] body = NON_BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Properties agentProps = new Properties();
        agentProps.setProperty("server.port", String.valueOf(port));
        when(virtualMachine.getAgentProperties()).thenReturn(agentProps);

        ActuatorProbe probe = new ActuatorProbe();

        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("probe returns empty on 200 but null body")
    void shouldReturnEmptyOn200NullBody() throws Exception {
        server.createContext("/actuator/beans", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        Properties agentProps = new Properties();
        agentProps.setProperty("server.port", String.valueOf(port));
        when(virtualMachine.getAgentProperties()).thenReturn(agentProps);

        ActuatorProbe probe = new ActuatorProbe();

        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("probe throws on GeneralSecurityException")
    void shouldThrowOnGeneralSecurityException() {
        try (org.mockito.MockedStatic<javax.net.ssl.SSLContext> sslStatic = org.mockito.Mockito.mockStatic(javax.net.ssl.SSLContext.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            sslStatic.when(() -> javax.net.ssl.SSLContext.getInstance("TLS"))
                     .thenThrow(new java.security.NoSuchAlgorithmException("Simulated"));
            
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ActuatorProbe(ActuatorAuth.basic("a", "b", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to initialise insecure TLS context");
        }
    }
}
