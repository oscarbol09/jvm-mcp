package dev.jvmmcp.core.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.tools.attach.VirtualMachine;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActuatorProbeTest {

    private static final String BEANS_PAYLOAD =
            "{\"contexts\":{\"application\":{\"beans\":{\"userService\":{}}}}}";
    private static final String NON_BEANS_PAYLOAD = "<html><body>Welcome</body></html>";

    private HttpServer server;
    private HttpsServer httpsServer;
    private int port;

    @Mock private VirtualMachine virtualMachine;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
    }

    /**
     * Starts a local HTTPS server (self-signed test keystore) that serves the beans payload on
     * /actuator/beans, and returns its port.
     */
    private int startHttpsBeansServer() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in =
                ActuatorProbeTest.class.getResourceAsStream("/actuator-test-keystore.p12")) {
            assertThat(in).as("test keystore resource").isNotNull();
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        httpsServer =
                HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(ctx));
        httpsServer.createContext(
                "/actuator/beans",
                exchange -> {
                    byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        httpsServer.start();
        return httpsServer.getAddress().getPort();
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
        server.createContext(
                "/actuator/beans",
                exchange -> {
                    byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
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
        assertThat(result.get().url()).startsWith("http://127.0.0.1:" + port);
        assertThat(result.get().url()).contains(":" + port + "/actuator/beans");
        assertThat(result.get().rawJson()).isEqualTo(BEANS_PAYLOAD);
    }

    @Test
    @DisplayName("probe finds a port given only as a --server.port argument in sun.java.command")
    void shouldProbePortFromCommandLineArgument() throws Exception {
        server.createContext(
                "/actuator/beans",
                exchange -> {
                    byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();

        Properties sysProps = new Properties();
        sysProps.setProperty(
                "sun.java.command",
                "my-service.jar --spring.profiles.active=prod --server.port=" + port);
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(new Properties());

        Optional<ActuatorProbe.ProbeResult> result = new ActuatorProbe().probe(virtualMachine);

        assertThat(result).isPresent();
        assertThat(result.get().url()).contains(":" + port + "/actuator/beans");
    }

    @Test
    @DisplayName("portsFromCommandLine reads --/-D server and management ports, management first")
    void shouldReadPortsFromCommandLine() {
        assertThat(ActuatorProbe.portsFromCommandLine("my-service.jar --server.port=8085"))
                .containsExactly("8085");
        assertThat(
                        ActuatorProbe.portsFromCommandLine(
                                "my-service.jar --server.port=8085 --management.server.port=9095"))
                .containsExactly("9095", "8085");
        assertThat(ActuatorProbe.portsFromCommandLine("com.example.App -Dserver.port=7070"))
                .containsExactly("7070");
        assertThat(
                        ActuatorProbe.portsFromCommandLine(
                                "app.jar --my.server.port=1 --server.portal=2 --server.port"))
                .isEmpty();
        assertThat(ActuatorProbe.portsFromCommandLine(null)).isEmpty();
    }

    @Test
    @DisplayName("probe handles getSystemProperties throwing exception gracefully")
    void shouldHandleVmPropertiesException() throws Exception {
        when(virtualMachine.getSystemProperties())
                .thenThrow(new IOException("Attach read failure"));

        ActuatorProbe probe = new ActuatorProbe();
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("probe returns empty when target endpoints do not contain beans")
    void shouldReturnEmptyWhenNoBeansPayloadFound() throws Exception {
        server.createContext(
                "/actuator/beans",
                exchange -> {
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
        server.createContext(
                "/actuator/beans",
                exchange -> {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                });
        server.createContext(
                "/beans",
                exchange -> {
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
    @DisplayName("fetchFromUrl preserves the original hostname in the Host header")
    void shouldPreserveHostnameWhenFetchingFromUrl() {
        AtomicReference<String> hostHeader = new AtomicReference<>();
        server.createContext(
                "/actuator/beans",
                exchange -> {
                    hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
                    byte[] body = BEANS_PAYLOAD.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.start();

        ActuatorProbe probe = new ActuatorProbe();
        Optional<String> result = probe.fetchFromUrl("http://localhost:" + port);

        assertThat(result).contains(BEANS_PAYLOAD);
        assertThat(hostHeader.get()).isEqualTo("localhost:" + port);
    }

    @Test
    @DisplayName("Insecure TLS configuration properly initialises TrustAllManager methods")
    void shouldInitializeInsecureTlsContext() throws Exception {
        ActuatorAuth auth = new ActuatorAuth(null, null, null, true);
        ActuatorProbe probe = new ActuatorProbe(auth);
        assertThat(probe).isNotNull();

        Class<?> trustAllClass =
                Class.forName("dev.jvmmcp.core.spring.ActuatorProbe$TrustAllManager");
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
        server.createContext(
                "/actuator/beans",
                exchange -> {
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
        server.createContext(
                "/actuator/beans",
                exchange -> {
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
        try (org.mockito.MockedStatic<javax.net.ssl.SSLContext> sslStatic =
                org.mockito.Mockito.mockStatic(
                        javax.net.ssl.SSLContext.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            sslStatic
                    .when(() -> javax.net.ssl.SSLContext.getInstance("TLSv1.2"))
                    .thenThrow(new java.security.NoSuchAlgorithmException("Simulated"));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> new ActuatorProbe(ActuatorAuth.basic("a", "b", true)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to initialise insecure TLS context");
        }
    }

    // ---------------------------------------------------------------------
    // Issue #88: HTTPS-secured Actuator discovery
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("probePort discovers an HTTPS actuator when insecure is enabled")
    void shouldDiscoverHttpsEndpointWhenInsecure() throws Exception {
        int httpsPort = startHttpsBeansServer();

        ActuatorProbe probe = new ActuatorProbe(new ActuatorAuth(null, null, null, true));
        Optional<String> json = probe.probePort(httpsPort);

        assertThat(json).isPresent();
        assertThat(json.get()).isEqualTo(BEANS_PAYLOAD);
    }

    @Test
    @DisplayName(
            "probe reports an https:// URL when TLS is hinted via management.server.ssl.enabled")
    void shouldReportHttpsUrlFromSslHint() throws Exception {
        int httpsPort = startHttpsBeansServer();

        Properties sysProps = new Properties();
        sysProps.setProperty("management.server.port", String.valueOf(httpsPort));
        sysProps.setProperty("management.server.ssl.enabled", "true");
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(new Properties());

        ActuatorProbe probe = new ActuatorProbe(new ActuatorAuth(null, null, null, true));
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isPresent();
        assertThat(result.get().url())
                .isEqualTo("https://127.0.0.1:" + httpsPort + "/actuator/beans");
        assertThat(result.get().rawJson()).isEqualTo(BEANS_PAYLOAD);
    }

    @Test
    @DisplayName("probe finds HTTPS actuator even without an SSL hint (fallback)")
    void shouldFallBackToHttpsWithoutHint() throws Exception {
        int httpsPort = startHttpsBeansServer();

        Properties sysProps = new Properties();
        sysProps.setProperty("server.port", String.valueOf(httpsPort));
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(new Properties());

        ActuatorProbe probe = new ActuatorProbe(new ActuatorAuth(null, null, null, true));
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isPresent();
        assertThat(result.get().url()).startsWith("https://127.0.0.1:" + httpsPort);
    }

    @Test
    @DisplayName("management port inherits server.ssl.enabled when not overridden")
    void shouldInheritServerSslHintForManagementPort() throws Exception {
        int httpsPort = startHttpsBeansServer();

        Properties sysProps = new Properties();
        sysProps.setProperty("management.server.port", String.valueOf(httpsPort));
        sysProps.setProperty("server.ssl.enabled", "true");
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(new Properties());

        ActuatorProbe probe = new ActuatorProbe(new ActuatorAuth(null, null, null, true));
        Optional<ActuatorProbe.ProbeResult> result = probe.probe(virtualMachine);

        assertThat(result).isPresent();
        assertThat(result.get().url()).startsWith("https://");
    }

    @Test
    @DisplayName("probePort does not trust a self-signed HTTPS endpoint without insecure")
    void shouldRejectSelfSignedHttpsWithoutInsecure() throws Exception {
        int httpsPort = startHttpsBeansServer();

        ActuatorProbe probe = new ActuatorProbe();
        assertThat(probe.probePort(httpsPort)).isEmpty();
    }

    @Test
    @DisplayName("probePort returns empty quickly when nothing is listening")
    void shouldReturnEmptyWhenPortClosed() {
        ActuatorProbe probe = new ActuatorProbe();
        assertThat(probe.probePort(1)).isEmpty();
    }
}
