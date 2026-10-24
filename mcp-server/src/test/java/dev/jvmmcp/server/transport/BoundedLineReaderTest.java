package dev.jvmmcp.server.transport;

import dev.jvmmcp.server.protocol.McpDispatcher;
import dev.jvmmcp.server.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedLineReaderTest {

    @Test
    @DisplayName("reads simple lines terminated by newline")
    void testReadsSimpleLines() throws Exception {
        String input = "line1\nline2\nline3\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 1024)) {
            assertThat(reader.readLine()).isEqualTo("line1");
            assertThat(reader.readLine()).isEqualTo("line2");
            assertThat(reader.readLine()).isEqualTo("line3");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("strips trailing carriage return on CRLF")
    void testStripsCarriageReturn() throws Exception {
        String input = "hello world\r\nnext line\r\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 1024)) {
            assertThat(reader.readLine()).isEqualTo("hello world");
            assertThat(reader.readLine()).isEqualTo("next line");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("handles empty lines and lines reaching EOF without newline")
    void testEmptyLinesAndEofWithoutNewline() throws Exception {
        String input = "\n\nlast line";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 1024)) {
            assertThat(reader.readLine()).isEmpty();
            assertThat(reader.readLine()).isEmpty();
            assertThat(reader.readLine()).isEqualTo("last line");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("counts UTF-8 multi-byte characters accurately by bytes")
    void testCountsUtf8BytesAccurately() throws Exception {
        // "ñ" is 2 bytes in UTF-8
        String input = "ñññ\n"; // 6 bytes + 1 newline
        // If maxBytes is 5, it should exceed the limit even though char count is 3
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 5)) {
            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class);
        }

        // If maxBytes is 6, it should succeed
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 6)) {
            assertThat(reader.readLine()).isEqualTo("ñññ");
        }
    }

    @Test
    @DisplayName("drains oversized line and cleanly reads subsequent line")
    void testDrainsOversizedLineAndRecovers() throws Exception {
        // Line 1 is 20 'a's (limit 10)
        // Line 2 is "valid line"
        String input = "a".repeat(20) + "\nvalid line\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 10, 4)) {
            
            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("10 bytes");

            // Subsequent read MUST cleanly return line 2
            assertThat(reader.readLine()).isEqualTo("valid line");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("oversized line successfully drained when its newline occurs within the limit")
    void testDrainsOversizedLineWhenNewlineWithinLimit() throws Exception {
        // maxBytes = 8, maxDrainBytes = 16
        // 9 'a's trigger drain, then 5 'b's and '\n' (6 drain bytes <= 16)
        String input = "a".repeat(9) + "b".repeat(5) + "\nok\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 8, 4, 16L)) {

            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("8 bytes");

            assertThat(reader.readLine()).isEqualTo("ok");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("continuous newline-free input exceeding the drain limit fails predictably")
    void testContinuousNewlineFreeInputExceedingDrainLimitFailsPredictably() {
        InputStream infiniteStream = new InfiniteByteInputStream((byte) 'x');
        try (BoundedLineReader reader = new BoundedLineReader(infiniteStream, 10, 16, 50L)) {
            assertThatThrownBy(reader::readLine)
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("exceeded maximum drain limit of 50 bytes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("EOF occurs during draining within allowed limit")
    void testEofDuringDrainingWithinLimit() throws Exception {
        // maxBytes = 10, maxDrainBytes = 20
        // Input has 15 bytes of 'x' followed by EOF (no newline)
        // 11th byte triggers drain, remaining 4 bytes drained before EOF
        String input = "x".repeat(15);
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 10, 4, 20L)) {

            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("10 bytes");

            // Next read encounters EOF cleanly
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("EOF occurs after exactly maxDrainBytes discarded bytes")
    void testEofAfterExactlyMaxDrainBytes() throws Exception {
        // maxBytes = 5, maxDrainBytes = 4
        // 6 bytes ("123456") trigger drain.
        // Draining reads exactly 4 bytes ("abcd") before hitting EOF without newline.
        String input = "123456" + "abcd";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 5, 2, 4L)) {

            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("5 bytes");

            // Subsequent read encounters EOF cleanly
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("newline exactly at the configured boundary is accepted")
    void testNewlineExactlyAtConfiguredBoundary() throws Exception {
        // maxBytes = 5, maxDrainBytes = 4
        // 6 bytes ("123456") trigger drain.
        // Draining reads: 'a', 'b', 'c', '\n' (newline at byte position 4 == maxDrainBytes)
        String input = "123456" + "abc\n" + "next\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 5, 2, 4L)) {

            assertThatThrownBy(reader::readLine)
                .isInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("5 bytes");

            assertThat(reader.readLine()).isEqualTo("next");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    @DisplayName("newline beyond the allowed drain limit is rejected with IOException")
    void testNewlineBeyondAllowedDrainLimit() {
        // maxBytes = 5, maxDrainBytes = 4
        // 6 bytes ("123456") trigger drain.
        // Draining reads: 'a', 'b', 'c', 'd' (4 non-newline bytes reach limit), then '\n' is at position 5
        String input = "123456" + "abcd\n" + "valid next line\n";
        try (BoundedLineReader reader = new BoundedLineReader(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 5, 2, 4L)) {

            assertThatThrownBy(reader::readLine)
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LineTooLargeException.class)
                .hasMessageContaining("exceeded maximum drain limit of 4 bytes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("reader does not continue processing stream after a drain-limit failure")
    void testReaderRejectsSubsequentReadsAfterDrainLimitFailure() {
        InputStream infiniteStream = new InfiniteByteInputStream((byte) 'z');
        try (BoundedLineReader reader = new BoundedLineReader(infiniteStream, 10, 8, 20L)) {
            // First read fails because drain limit is exceeded
            assertThatThrownBy(reader::readLine)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeded maximum drain limit");

            // Subsequent read MUST immediately fail without reading further
            assertThatThrownBy(reader::readLine)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Stream unrecoverable");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("caller McpTransport terminates with fatal error on drain-limit failure")
    void testCallerMcpTransportAbortsOnDrainLimitFailure() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        // Continuous stream of 'x' without newlines
        InputStream infiniteStream = new InfiniteByteInputStream((byte) 'x');
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = transport.start(infiniteStream, out);
        // McpTransport must abort with fatal error exit code 1
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("derives maxDrainBytes from maxBytes without integer overflow")
    void testDerivesMaxDrainBytesWithoutIntegerOverflow() {
        assertThatCode(() -> {
            try (BoundedLineReader reader = new BoundedLineReader(
                new ByteArrayInputStream(new byte[0]), Integer.MAX_VALUE)) {
                assertThat(reader.readLine()).isNull();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validates constructor parameters")
    void testConstructorValidation() {
        assertThatThrownBy(() -> new BoundedLineReader(null, 100))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(new byte[0]), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(new byte[0]), -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(new byte[0]), 10, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(new byte[0]), 10, 4, 0L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxDrainBytes must be positive");
        assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(new byte[0]), 10, 4, -5L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxDrainBytes must be positive");
    }

    @Test
    @DisplayName("close closes stream and rejects subsequent reads")
    void testClose() throws Exception {
        BoundedLineReader reader = new BoundedLineReader(new ByteArrayInputStream("data\n".getBytes(StandardCharsets.UTF_8)), 100);
        reader.close();
        assertThatThrownBy(reader::readLine)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Stream closed");
    }

    /**
     * Deterministic InputStream that generates infinite bytes on demand without buffering or allocating memory.
     */
    static class InfiniteByteInputStream extends InputStream {
        private final byte byteToReturn;

        InfiniteByteInputStream(byte byteToReturn) {
            this.byteToReturn = byteToReturn;
        }

        @Override
        public int read() {
            return byteToReturn & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            Arrays.fill(b, off, off + len, byteToReturn);
            return len;
        }
    }
}
