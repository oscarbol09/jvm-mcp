package dev.jvmmcp.server.tool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Shared helpers for resolving PostgreSQL JDBC URLs from the environment, so the {@code pg} CLI
 * command and the MCP tool handler build the effective URL identically.
 */
public final class PostgresUrls {

    private PostgresUrls() {}

    /**
     * Returns {@code url} with the database from {@code PGDATABASE} (or the legacy {@code
     * PG_DATABASE}) appended when the JDBC URL carries no database path. An explicit database in
     * the URL always wins, and the URL is returned unchanged when no database environment variable
     * is set.
     */
    public static String withDefaultDatabase(String url, Function<String, String> envProvider) {
        if (url == null || !url.startsWith("jdbc:")) {
            return url;
        }
        URI uri = URI.create(url.substring("jdbc:".length()));
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return url;
        }
        String database = firstEnv(envProvider, "PGDATABASE", "PG_DATABASE");
        if (database == null || database.isEmpty()) {
            return url;
        }
        String encodedDatabase =
                URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
        return "jdbc:postgresql://"
                + uri.getRawAuthority()
                + "/"
                + encodedDatabase
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "")
                + (uri.getRawFragment() != null ? "#" + uri.getRawFragment() : "");
    }

    private static String firstEnv(
            Function<String, String> envProvider, String canonical, String legacy) {
        String value = envProvider.apply(canonical);
        return value != null ? value : envProvider.apply(legacy);
    }
}
