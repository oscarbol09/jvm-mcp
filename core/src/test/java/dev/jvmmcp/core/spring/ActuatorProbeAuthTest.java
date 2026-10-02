package dev.jvmmcp.core.spring;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorProbeAuthTest {

    private static final String BEANS_JSON = "{\"contexts\":{\"app\":{\"beans\":{}}}}";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            String path = exchange.getRequestURI().getPath();
            boolean servesBeans = path.equals("/actuator/beans") || path.equals("/management/beans");
            byte[] body = (servesBeans ? BEANS_JSON : "not found").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(servesBeans ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("Basic auth sends an Authorization header with base64(user:password)")
    void shouldSendBasicAuthHeader() {
        ActuatorProbe probe = new ActuatorProbe(ActuatorAuth.basic("admin", "s3cret", false));

        Optional<String> json = probe.fetchFromUrl(baseUrl);

        String expected = "Basic " + Base64.getEncoder()
            .encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(json).contains(BEANS_JSON);
        assertThat(lastAuthHeader.get()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Bearer token sends 'Authorization: Bearer <token>'")
    void shouldSendBearerHeader() {
        ActuatorProbe probe = new ActuatorProbe(ActuatorAuth.bearer("abc.def.ghi", false));

        Optional<String> json = probe.fetchFromUrl(baseUrl);

        assertThat(json).isPresent();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer abc.def.ghi");
    }

    @Test
    @DisplayName("No credentials means no Authorization header")
    void shouldNotSendHeaderWithoutCredentials() {
        ActuatorProbe probe = new ActuatorProbe();

        Optional<String> json = probe.fetchFromUrl(baseUrl);

        assertThat(json).isPresent();
        assertThat(lastAuthHeader.get()).isNull();
    }

    @Test
    @DisplayName("probePort propagates the Authorization header too")
    void shouldSendHeaderOnProbePort() {
        ActuatorProbe probe = new ActuatorProbe(ActuatorAuth.bearer("tok", false));

        Optional<String> json = probe.probePort(server.getAddress().getPort());

        assertThat(json).isPresent();
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer tok");
    }

    @Test
    @DisplayName("Falls back to /beans when the app uses a custom management base path")
    void shouldTryCustomBasePathUrl() {
        // server only serves /management/beans and /actuator/beans; a full custom URL is used as-is
        ActuatorProbe probe = new ActuatorProbe(ActuatorAuth.bearer("tok", false));

        Optional<String> json = probe.fetchFromUrl(baseUrl + "/management/beans");

        assertThat(json).contains(BEANS_JSON);
        assertThat(requestedPaths).containsExactly("/management/beans");
    }

    @Test
    @DisplayName("Base URL tries /actuator/beans first, then /beans")
    void shouldBuildCandidateUrls() {
        assertThat(ActuatorProbe.candidateBeansUrls("https://host:8443/"))
            .containsExactly("https://host:8443/actuator/beans", "https://host:8443/beans");
        assertThat(ActuatorProbe.candidateBeansUrls("https://host/management/beans"))
            .containsExactly("https://host/management/beans");
    }

    @Test
    @DisplayName("Auth validation rejects Basic + Bearer together and half-filled Basic credentials")
    void shouldValidateCombinations() {
        assertThat(new ActuatorAuth("u", "p", "tok", false).validate()).isPresent();
        assertThat(new ActuatorAuth("u", null, null, false).validate()).isPresent();
        assertThat(new ActuatorAuth(null, "p", null, false).validate()).isPresent();
        assertThat(ActuatorAuth.basic("u", "p", false).validate()).isEmpty();
        assertThat(ActuatorAuth.bearer("tok", false).validate()).isEmpty();
        assertThat(ActuatorAuth.NONE.validate()).isEmpty();
    }

    @Test
    @DisplayName("toString never leaks the password or token")
    void shouldNotLeakSecretsInToString() {
        assertThat(ActuatorAuth.basic("admin", "s3cret", false).toString())
            .doesNotContain("s3cret");
        assertThat(ActuatorAuth.bearer("supersecrettoken", false).toString())
            .doesNotContain("supersecrettoken");
    }
}
