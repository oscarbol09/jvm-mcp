package dev.jvmmcp.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FrameworkTest {
    @Test
    void shouldReturnCorrectDisplayNames() {
        assertThat(Framework.SPRING_BOOT.getDisplayName()).isEqualTo("Spring Boot");
        assertThat(Framework.valueOf("QUARKUS")).isEqualTo(Framework.QUARKUS);
        assertThat(Framework.values()).contains(Framework.MICRONAUT, Framework.PLAIN_JAVA, Framework.UNKNOWN);
    }
}
