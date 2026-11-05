package dev.jvmmcp.core.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.jvmmcp.core.model.ThreadDump;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ThreadDumpVirtualOptInTest {

    @Test
    void shouldSkipVirtualReaderByDefault() throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L);

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
            verifyNoInteractions(reader);
        }
    }

    @Test
    void shouldSkipVirtualReaderForExplicitMonitorFlags() throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L, false, false);

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
            verifyNoInteractions(reader);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "21.0.1", "25"})
    void shouldReadVirtualThreadsWhenOptedInOnSupportedVersion(String version) throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        when(reader.readVirtualThreads(any())).thenReturn(Optional.of(List.of()));
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L, true, true, true, version);

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNotNull().isEmpty();
            verify(reader).readVirtualThreads(any());
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"8", "1.8", "11", "17", "20", "garbage", ""})
    void shouldSkipVirtualReaderOnUnsupportedVersionEvenWhenOptedIn(String version)
            throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L, true, true, true, version);

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
            verifyNoInteractions(reader);
        }
    }

    @Test
    void shouldSkipVirtualReaderWhenExplicitFalseOnSupportedVersion() throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L, true, true, false, "21");

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
            verifyNoInteractions(reader);
        }
    }

    @Test
    void shouldReturnNullVirtualThreadsWhenReaderThrowsAndPreservePlatformThreads()
            throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        when(reader.readVirtualThreads(any())).thenThrow(new RuntimeException("boom"));
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            ThreadDump dump = client.getThreadDump(42L, true, true, true, "21");

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
            verify(reader).readVirtualThreads(any());
        }
    }

    @Test
    void shouldPropagateDumpTooLargeExceptionOnOptIn() throws Exception {
        VirtualThreadDumpReader reader = mock(VirtualThreadDumpReader.class);
        VirtualThreadDumpReader.DumpTooLargeException thrown =
                new VirtualThreadDumpReader.DumpTooLargeException(
                        VirtualThreadDumpReader.MAX_DUMP_BYTES + 1,
                        VirtualThreadDumpReader.MAX_DUMP_BYTES);
        when(reader.readVirtualThreads(any())).thenThrow(thrown);
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), reader)) {
            assertThatThrownBy(() -> client.getThreadDump(42L, true, true, true, "21"))
                    .isSameAs(thrown);
            verify(reader).readVirtualThreads(any());
        }
    }

    @Test
    void shouldAllowNullReaderOnOptIn() throws Exception {
        try (ThreadMXBeanClient client =
                new ThreadMXBeanClient(ManagementFactory.getPlatformMBeanServer(), null)) {
            ThreadDump dump = client.getThreadDump(42L, true, true, true, "21");

            assertThat(dump.threads()).isNotEmpty();
            assertThat(dump.virtualThreads()).isNull();
        }
    }
}
