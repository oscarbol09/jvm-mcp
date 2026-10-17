package dev.jvmmcp.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJsonTest {

    @Test
    @DisplayName("toJson should throw IllegalArgumentException when exceeding max depth")
    void shouldRejectExcessiveSerializationDepth() {
        Map<String, Object> root = new java.util.HashMap<>();
        Map<String, Object> current = root;
        for (int i = 0; i < 120; i++) {
            Map<String, Object> next = new java.util.HashMap<>();
            current.put("child", next);
            current = next;
        }
        assertThatThrownBy(() -> SimpleJson.toJson(root))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("depth");
    }

    @Test
    @DisplayName("parseObject should parse complex nested JSON with strings, arrays, and numbers")
    void shouldParseNestedJsonObject() {
        String json = """
            {
                "serviceName": "order-service",
                "port": 8080,
                "negativeVal": -42,
                "floatingVal": 3.1415,
                "expVal": 1.2e3,
                "active": true,
                "disabled": false,
                "emptyValue": null,
                "tags": ["spring", "mcp", "jvm", true, false, null, 100],
                "config": {
                    "timeoutMs": 1500,
                    "ratio": 0.85
                },
                "standardString": "value",
                "emptyObj": {},
                "emptyArr": []
            }
            """;

        Map<String, Object> map = SimpleJson.parseObject(json);

        assertThat(map).isNotNull();
        assertThat(map.get("serviceName")).isEqualTo("order-service");
        assertThat(map.get("port")).isEqualTo(8080);
        assertThat(map.get("negativeVal")).isEqualTo(-42);
        assertThat(map.get("floatingVal")).isEqualTo(3.1415);
        assertThat(map.get("expVal")).isEqualTo(1200.0);
        assertThat(map.get("active")).isEqualTo(true);
        assertThat(map.get("disabled")).isEqualTo(false);
        assertThat(map.get("emptyValue")).isNull();
        assertThat(map.get("standardString")).isEqualTo("value");

        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) map.get("tags");
        assertThat(tags).hasSize(7);
        assertThat(tags.get(0)).isEqualTo("spring");
        assertThat(tags.get(3)).isEqualTo(true);
        assertThat(tags.get(4)).isEqualTo(false);
        assertThat(tags.get(5)).isNull();
        assertThat(tags.get(6)).isEqualTo(100);

        @SuppressWarnings("unchecked")
        Map<String, Object> emptyObj = (Map<String, Object>) map.get("emptyObj");
        assertThat(emptyObj).isEmpty();

        @SuppressWarnings("unchecked")
        List<Object> emptyArr = (List<Object>) map.get("emptyArr");
        assertThat(emptyArr).isEmpty();
    }

    @Test
    @DisplayName("parseObject should handle all escape sequences and unicode characters")
    void shouldHandleEscapeSequences() {
        String json = "{\"msg\": \"\\\" \\' \\\\ \\/ \\b \\f \\n \\r \\t \\u0041 \\z\"}";
        Map<String, Object> map = SimpleJson.parseObject(json);

        assertThat(map.get("msg")).isEqualTo("\" ' \\ / \b \f \n \r \t A z");
    }

    @Test
    @DisplayName("parse handles null and blank inputs gracefully")
    void shouldHandleNullAndBlankStrings() {
        assertThat(SimpleJson.parse(null)).isNull();
        assertThat(SimpleJson.parse("   ")).isNull();
    }

    @Test
    @DisplayName("parse numbers handles large long numbers and numbers exceeding long capacity")
    void shouldParseVariousNumberFormats() {
        assertThat(SimpleJson.parse("5000000000")).isEqualTo(5000000000L);
        assertThat(SimpleJson.parse("-0.5e-2")).isEqualTo(-0.005);
        assertThat(SimpleJson.parse("10000000000000000000000000000000000000")).isInstanceOf(Double.class);
    }

    @Test
    @DisplayName("parseObject throws IllegalArgumentException for non-object root")
    void shouldThrowWhenNotAnObject() {
        assertThatThrownBy(() -> SimpleJson.parseObject("[1, 2, 3]"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Expected JSON Object");

        assertThatThrownBy(() -> SimpleJson.parseObject("\"just a string\""))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SimpleJson.parseObject(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse throws IllegalArgumentException for syntax errors and unterminated tokens")
    void shouldThrowForMalformedJson() {
        // Unexpected starting character
        assertThatThrownBy(() -> SimpleJson.parse("@invalid"))
            .isInstanceOf(IllegalArgumentException.class);

        // Unterminated string
        assertThatThrownBy(() -> SimpleJson.parse("{\"key\": \"unterminated"))
            .isInstanceOf(IllegalArgumentException.class);

        // Missing colon in object
        assertThatThrownBy(() -> SimpleJson.parse("{\"key\" \"val\"}"))
            .isInstanceOf(IllegalArgumentException.class);

        // Missing comma in object
        assertThatThrownBy(() -> SimpleJson.parse("{\"a\": 1 \"b\": 2}"))
            .isInstanceOf(IllegalArgumentException.class);

        // Missing comma in array
        assertThatThrownBy(() -> SimpleJson.parse("[1 2 3]"))
            .isInstanceOf(IllegalArgumentException.class);

        // Invalid boolean and null tokens
        assertThatThrownBy(() -> SimpleJson.parse("truth"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimpleJson.parse("nil"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse and toJson should round-trip successfully for strings with escape sequences and unicode")
    void shouldRoundTripStrings() {
        String original = "Here is a string with \n newline, \t tab, \u001b escape, \" quotes \", and unicode \u2603 \uD83D\uDCA9.";
        String json = SimpleJson.toJson(original);
        Object parsed = SimpleJson.parse(json);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("parse should strictly reject non-standard single-quoted strings")
    void shouldRejectSingleQuotedStrings() {
        assertThatThrownBy(() -> SimpleJson.parse("'single'"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unexpected character");

        assertThatThrownBy(() -> SimpleJson.parse("{\"key\": 'value'}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parse should strictly reject trailing content after a valid JSON value")
    void shouldRejectTrailingContentAfterJsonValue() {
        assertThatThrownBy(() -> SimpleJson.parse("{\"jsonrpc\":\"2.0\"} basura"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Trailing content");

        assertThatThrownBy(() -> SimpleJson.parse("true anything"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Trailing content");

        assertThatThrownBy(() -> SimpleJson.parse("[1, 2, 3] trailing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Trailing content");

        assertThatThrownBy(() -> SimpleJson.parse("\"valid string\" extra"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Trailing content");
    }

    @ParameterizedTest(name = "Reject malformed number ''{0}''")
    @ValueSource(strings = {"-", "{\"val\":-}", "[-,1]", "1.", "[1.]", "1e", "1e+", "-1.5e-", "-.5"})
    @DisplayName("parse should reject malformed numbers as JSON errors instead of leaking NumberFormatException")
    void shouldRejectMalformedNumbers(String json) {
        assertThatThrownBy(() -> SimpleJson.parse(json))
            .as("parsing %s", json)
            .isExactlyInstanceOf(IllegalArgumentException.class) // NumberFormatException is a subclass, so the type must match exactly
            .hasMessageContaining("Invalid number")
            .hasMessageContaining("position");
    }

    @Test
    @DisplayName("parse should report the position of a bare minus token")
    void shouldReportPositionOfBareMinus() {
        // index 0='{', 1='"', 2='v', 3='a', 4='l', 5='"', 6=':', 7='-'
        assertThatThrownBy(() -> SimpleJson.parse("{\"val\":-}"))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("position 7");
    }

    @ParameterizedTest(name = "Reject malformed unicode escape in ''{0}''")
    @ValueSource(strings = {"\"\\u00zz\"", "\"\\u12\"", "\"\\uZZZZ\"", "\"\\u00\"",
                            "{\"k\":\"\\u0g00\"}", "{\"k\":\"\\u"})
    @DisplayName("parse should reject malformed \\u escapes as JSON errors instead of leaking NumberFormatException")
    void shouldRejectMalformedUnicodeEscapes(String json) {
        assertThatThrownBy(() -> SimpleJson.parse(json))
            .as("parsing %s", json)
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid unicode escape")
            .hasMessageContaining("position");
    }

    @Test
    @DisplayName("parse should pinpoint the offending character inside a \\u escape")
    void shouldPinpointOffendingHexDigit() {
        assertThatThrownBy(() -> SimpleJson.parse("\"\\u00zz\""))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("position 5")
            .hasMessageContaining("'z'");
    }

    @Test
    @DisplayName("parse should not route non-ASCII digits into the number parser")
    void shouldRejectNonAsciiDigits() {
        // U+0661 ARABIC-INDIC DIGIT ONE satisfies Character.isDigit but is never valid in JSON
        assertThatThrownBy(() -> SimpleJson.parse("{\"val\":\u0661}"))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unexpected character");
    }

    @ParameterizedTest(name = "Parse valid number ''{0}'' as ''{1}''")
    @CsvSource({
        "0, 0",
        "-1, -1",
        "  -42  , -42",
        "2147483647, 2147483647",
        "1.5, 1.5",
        "-0.125, -0.125",
        "-1.5e-3, -0.0015",
        "2E3, 2000.0"
    })
    @DisplayName("parse should still accept every valid JSON number shape")
    void shouldParseValidNumbers(String json, String expected) {
        Object parsed = SimpleJson.parse(json);
        assertThat(parsed).isInstanceOf(Number.class);
        Number number = (Number) parsed;
        if (expected.contains(".")) {
            assertThat(number.doubleValue()).isEqualTo(Double.parseDouble(expected));
        } else {
            assertThat(number.longValue()).isEqualTo(Long.parseLong(expected));
        }
    }

    @Test
    @DisplayName("parse should decode valid \\u escapes next to numbers")
    void shouldDecodeValidUnicodeEscapes() {
        assertThat(SimpleJson.parse("\"\\u0041\\u00e9\\u4f60\"")).isEqualTo("A\u00e9\u4f60");
        Map<String, Object> parsed = SimpleJson.parseObject(
            "{\"name\":\"caf\\u00e9\",\"count\":-3,\"ratio\":1.25e2}");
        assertThat(parsed).containsEntry("name", "caf\u00e9");
        assertThat(parsed.get("count")).isEqualTo(-3);
        assertThat(((Number) parsed.get("ratio")).doubleValue()).isEqualTo(125.0);
    }

    public record SampleRecord(String title, int count) {}

    @Test
    @DisplayName("toJson should serialize public Java records cleanly")
    void shouldSerializeRecordWithoutRequiringAccessibleOverride() {
        SampleRecord record = new SampleRecord("metrics", 42);
        String json = SimpleJson.toJson(record);
        assertThat(json).contains("\"title\":\"metrics\"");
        assertThat(json).contains("\"count\":42");
    }
}

