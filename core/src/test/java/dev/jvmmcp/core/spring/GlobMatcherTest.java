package dev.jvmmcp.core.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class GlobMatcherTest {

    @ParameterizedTest(name = "Glob ''{1}'' matches ''{0}'' -> {2}")
    @CsvSource({
        "com.example.OrderService, *Service*, true",
        "orderService, *service*, true",
        "PaymentController, *Controller, true",
        "PaymentController, *Service, false",
        "userRepository, User*, true",
        "authManager, *auth*, true",
        "com.example.repo.UserRepo, com.example.*, true",
        "org.springframework.boot.Service, *spring*, true",
        "anything, *, true",
        "anything, '', true",
        "anything, '   ', true",
        "ServiceA, Service?, true",
        "ServiceAB, Service?, false",
        "com.example(v1)[core], com.example(*)[*], true",
        "test+item$price^val{1}|2\\3, *+item$price^*, true"
    })
    @DisplayName("GlobMatcher should match patterns case-insensitively with glob wildcards")
    void shouldMatchGlobPatterns(String text, String pattern, boolean expected) {
        assertThat(GlobMatcher.matches(text, pattern)).isEqualTo(expected);
    }

    @Test
    @DisplayName("matches returns false when text is null and pattern is specific")
    void shouldReturnFalseForNullText() {
        assertThat(GlobMatcher.matches(null, "somePattern")).isFalse();
        assertThat(GlobMatcher.matches(null, null)).isTrue();
        assertThat(GlobMatcher.matches(null, "*")).isTrue();
    }
}
