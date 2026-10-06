package dev.jvmmcp.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ThreadDetailTest {
    @Test
    void shouldReturnNullWhenThreadInfoIsNull() {
        assertThat(ThreadDetail.from(null)).isNull();
    }
}
