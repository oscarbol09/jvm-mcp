package dev.jvmmcp.core.spring;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Connection options for reaching a protected Actuator endpoint: Basic auth, Bearer token,
 * and optional trust-all TLS for self-signed certificates.
 *
 * <p>Secrets are never included in {@link #toString()}.
 */
public record ActuatorAuth(String user, String password, String bearerToken, boolean insecure) {

    public static final ActuatorAuth NONE = new ActuatorAuth(null, null, null, false);

    public static ActuatorAuth basic(String user, String password, boolean insecure) {
        return new ActuatorAuth(user, password, null, insecure);
    }

    public static ActuatorAuth bearer(String token, boolean insecure) {
        return new ActuatorAuth(null, null, token, insecure);
    }

    /**
     * Returns a human-readable validation error, or empty if the combination is valid.
     */
    public Optional<String> validate() {
        boolean hasUser = notBlank(user);
        boolean hasPassword = password != null && !password.isEmpty();
        boolean hasToken = notBlank(bearerToken);

        if (hasToken && (hasUser || hasPassword)) {
            return Optional.of("Use either --actuator-token or --actuator-user/--actuator-password, not both.");
        }
        if (hasUser != hasPassword) {
            return Optional.of("--actuator-user and --actuator-password must be provided together.");
        }
        return Optional.empty();
    }

    /**
     * Value for the {@code Authorization} header, or empty when no credentials are configured.
     */
    public Optional<String> authorizationHeader() {
        if (notBlank(bearerToken)) {
            return Optional.of("Bearer " + bearerToken.trim());
        }
        if (notBlank(user) && password != null && !password.isEmpty()) {
            String raw = user + ":" + password;
            return Optional.of("Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        return Optional.empty();
    }

    public boolean hasCredentials() {
        return authorizationHeader().isPresent();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public String toString() {
        String mode = notBlank(bearerToken) ? "bearer" : (notBlank(user) ? "basic" : "none");
        return "ActuatorAuth[mode=" + mode + ", insecure=" + insecure + "]";
    }
}
