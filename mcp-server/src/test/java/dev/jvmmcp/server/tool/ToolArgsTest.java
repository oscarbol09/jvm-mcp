package dev.jvmmcp.server.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolArgsTest {

    @Test
    @DisplayName("asMap casts Map objects and returns null for non-maps")
    void testAsMap() {
        Map<String, Object> map = Map.of("key", "value");
        assertThat(ToolArgs.asMap(map)).isSameAs(map);
        assertThat(ToolArgs.asMap(null)).isNull();
        assertThat(ToolArgs.asMap("not a map")).isNull();
        assertThat(ToolArgs.asMap(123)).isNull();
    }

    @Test
    @DisplayName("getStringArg returns string value when present")
    void testGetStringArgSuccess() {
        Map<String, Object> args = Map.of("name", "testVal");
        assertThat(ToolArgs.getStringArg(args, "name", true)).isEqualTo("testVal");
        assertThat(ToolArgs.getStringArg(args, "name", false)).isEqualTo("testVal");
    }

    @Test
    @DisplayName("getStringArg handles missing keys based on required flag")
    void testGetStringArgMissing() {
        Map<String, Object> args = Map.of("other", "val");
        assertThat(ToolArgs.getStringArg(args, "missing", false)).isNull();
        assertThatThrownBy(() -> ToolArgs.getStringArg(args, "missing", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: missing");

        assertThat(ToolArgs.getStringArg(null, "missing", false)).isNull();
        assertThatThrownBy(() -> ToolArgs.getStringArg(null, "missing", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: missing");
    }

    @Test
    @DisplayName("getStringArg throws exception for non-string values")
    void testGetStringArgInvalidType() {
        Map<String, Object> args = Map.of("count", 42);
        assertThatThrownBy(() -> ToolArgs.getStringArg(args, "count", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Argument 'count' must be a string");
    }

    @Test
    @DisplayName("getStringArg handles null value for present key")
    void testGetStringArgNullValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("nullable", null);
        assertThat(ToolArgs.getStringArg(args, "nullable", false)).isNull();
    }

    @Test
    @DisplayName("getLongArg returns long value for numbers")
    void testGetLongArgSuccess() {
        Map<String, Object> args = Map.of(
            "intVal", 10,
            "longVal", 5000000000L,
            "doubleVal", 99.0,
            "strLong", "123456"
        );
        assertThat(ToolArgs.getLongArg(args, "intVal", true)).isEqualTo(10L);
        assertThat(ToolArgs.getLongArg(args, "longVal", false)).isEqualTo(5000000000L);
        assertThat(ToolArgs.getLongArg(args, "doubleVal", false)).isEqualTo(99L);
        assertThat(ToolArgs.getLongArg(args, "strLong", false)).isEqualTo(123456L);
    }

    @Test
    @DisplayName("getLongArg handles missing keys based on required flag")
    void testGetLongArgMissing() {
        Map<String, Object> args = Map.of("other", 100);
        assertThat(ToolArgs.getLongArg(args, "missing", false)).isEqualTo(0L);
        assertThatThrownBy(() -> ToolArgs.getLongArg(args, "missing", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: missing");

        assertThat(ToolArgs.getLongArg(null, "missing", false)).isEqualTo(0L);
        assertThatThrownBy(() -> ToolArgs.getLongArg(null, "missing", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: missing");
    }

    @Test
    @DisplayName("getLongArg throws exception for non-number values and invalid strings")
    void testGetLongArgInvalidType() {
        Map<String, Object> args = Map.of("str", "not-a-number");
        assertThatThrownBy(() -> ToolArgs.getLongArg(args, "str", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a number");

        Map<String, Object> nonStringArgs = Map.of("bool", true);
        assertThatThrownBy(() -> ToolArgs.getLongArg(nonStringArgs, "bool", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a number");
    }

    @Test
    @DisplayName("getPositiveLongArg enforces positive values")
    void testGetPositiveLongArg() {
        Map<String, Object> valid = Map.of("pid", 12345L);
        assertThat(ToolArgs.getPositiveLongArg(valid, "pid", true)).isEqualTo(12345L);

        Map<String, Object> zero = Map.of("pid", 0L);
        assertThatThrownBy(() -> ToolArgs.getPositiveLongArg(zero, "pid", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a positive number");

        Map<String, Object> negative = Map.of("pid", -10L);
        assertThatThrownBy(() -> ToolArgs.getPositiveLongArg(negative, "pid", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a positive number");
    }

    @Test
    @DisplayName("getIntArg applies bounds and defaults")
    void testGetIntArg() {
        assertThat(ToolArgs.getIntArg(null, "limit", 100, 1, 1000)).isEqualTo(100);
        assertThat(ToolArgs.getIntArg(Map.of(), "limit", 100, 1, 1000)).isEqualTo(100);

        // Clamping to min
        assertThat(ToolArgs.getIntArg(Map.of("limit", -50), "limit", 100, 1, 1000)).isEqualTo(1);

        // Clamping to max
        assertThat(ToolArgs.getIntArg(Map.of("limit", 5000), "limit", 100, 1, 1000)).isEqualTo(1000);

        // Within bounds
        assertThat(ToolArgs.getIntArg(Map.of("limit", 250), "limit", 100, 1, 1000)).isEqualTo(250);

        // Numeric string support
        assertThat(ToolArgs.getIntArg(Map.of("limit", "250"), "limit", 100, 1, 1000)).isEqualTo(250);

        // Invalid numeric string
        assertThatThrownBy(() -> ToolArgs.getIntArg(Map.of("limit", "invalid"), "limit", 100, 1, 1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a number");
    }

    @Test
    @DisplayName("getStringArg allows null for optional arguments")
    void testGetStringArgOptionalNullValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("url", null);

        assertThat(ToolArgs.getStringArg(args, "url", false)).isNull();
    }

    @Test
    @DisplayName("getStringArg allows blank optional arguments")
    void testGetStringArgOptionalBlankValue() {
        Map<String, Object> args = Map.of("url", "   ");

        assertThat(ToolArgs.getStringArg(args, "url", false)).isEqualTo("   ");
    }

    @Test
    @DisplayName("getStringArg rejects null for required arguments")
    void testGetStringArgRequiredNullValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("url", null);

        assertThatThrownBy(() -> ToolArgs.getStringArg(args, "url", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required argument: url");
    }

    @Test
    @DisplayName("getStringArg rejects blank strings for required arguments")
    void testGetStringArgRequiredBlankValue() {
        Map<String, Object> args = Map.of("url", " ");

        assertThatThrownBy(() -> ToolArgs.getStringArg(args, "url", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Argument 'url' cannot be blank");
    }

    @Test
    @DisplayName("getLongArg returns zero for null optional arguments")
    void testGetLongArgNullOptionalValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("limit", null);

        assertThat(ToolArgs.getLongArg(args, "limit", false)).isEqualTo(0L);
    }

    @Test
    @DisplayName("getLongArg rejects null required arguments")
    void testGetLongArgNullRequiredValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("limit", null);

        assertThatThrownBy(() -> ToolArgs.getLongArg(args, "limit", true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a number");
    }

    @Test
    @DisplayName("getIntArg returns default for null values")
    void testGetIntArgNullValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("limit", null);

        assertThat(ToolArgs.getIntArg(args, "limit", 100, 1, 1000))
            .isEqualTo(100);
    }

    @Test
    @DisplayName("getBooleanArg handles missing and null values")
    void testGetBooleanArgDefaultValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("enabled", null);

        assertThat(ToolArgs.getBooleanArg(null, "enabled", true)).isTrue();
        assertThat(ToolArgs.getBooleanArg(Map.of(), "enabled", false)).isFalse();
        assertThat(ToolArgs.getBooleanArg(args, "enabled", true)).isTrue();
    }

    @Test
    @DisplayName("getBooleanArg supports booleans and strings")
    void testGetBooleanArgValues() {
        assertThat(ToolArgs.getBooleanArg(
            Map.of("enabled", true), "enabled", false)).isTrue();

        assertThat(ToolArgs.getBooleanArg(
            Map.of("enabled", false), "enabled", true)).isFalse();

        assertThat(ToolArgs.getBooleanArg(
            Map.of("enabled", " true "), "enabled", false)).isTrue();

        assertThat(ToolArgs.getBooleanArg(
            Map.of("enabled", "false"), "enabled", true)).isFalse();
    }

    @Test
    @DisplayName("getBooleanArg rejects unsupported value types")
    void testGetBooleanArgInvalidType() {
        assertThatThrownBy(() ->
            ToolArgs.getBooleanArg(Map.of("enabled", 1), "enabled", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Argument 'enabled' must be a boolean");
    }
}
