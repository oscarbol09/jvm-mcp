package dev.jvmmcp.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import net.jqwik.api.*;

class SimpleJsonPropertyTest {

    @Property
    void validKeyValuesAlwaysParseSuccessfully(
            @ForAll("jsonSafeString") String key, @ForAll("jsonSafeString") String value) {
        String json = "{\"" + escapeJson(key) + "\":\"" + escapeJson(value) + "\"}";
        Map<String, Object> result = SimpleJson.parseObject(json);

        assertThat(result).isNotNull();
        assertThat(result).containsKey(key);
        assertThat(result.get(key)).isEqualTo(value);
    }

    @Property
    void numericValuesParseToExpectedTypes(@ForAll long integerVal, @ForAll double floatVal) {
        if (!Double.isFinite(floatVal)) return;

        String json = "{\"intVal\":" + integerVal + ",\"floatVal\":" + floatVal + "}";
        Map<String, Object> result = SimpleJson.parseObject(json);

        assertThat(result).isNotNull();
        assertThat(((Number) result.get("intVal")).longValue()).isEqualTo(integerVal);
        assertThat(((Number) result.get("floatVal")).doubleValue())
                .isEqualTo(floatVal, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Property
    void booleanValuesParseAccurately(@ForAll boolean boolVal) {
        String json = "{\"flag\":" + boolVal + "}";
        Map<String, Object> result = SimpleJson.parseObject(json);

        assertThat(result).isNotNull();
        assertThat(result.get("flag")).isEqualTo(boolVal);
    }

    @Property
    void malformedRandomInputsGracefullyRejectWithIllegalArgumentException(
            @ForAll("malformedTokens") String malformed) {
        assertThatThrownBy(() -> SimpleJson.parseObject(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<String> jsonSafeString() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(
                        s ->
                                !s.contains("\"")
                                        && !s.contains("\\")
                                        && s.chars().noneMatch(c -> c < 0x20));
    }

    @Provide
    Arbitrary<String> malformedTokens() {
        return Arbitraries.of(
                "{unquotedKey: 123}",
                "{\"key\": }",
                "{",
                "}",
                "[1, 2, ",
                "{\"broken\": [1, 2}",
                "true false",
                "null null");
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
