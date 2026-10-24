package dev.jvmmcp.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A streaming bounded line reader that reads UTF-8 lines from an {@link InputStream}.
 * Reads into a fixed-size internal buffer, counts accumulated line bytes against {@code maxBytes},
 * and if an oversized line is encountered, drains the remaining line from the stream up to the
 * next newline or EOF, bounded by {@code maxDrainBytes}.
 * <p>
 * If a newline is encountered within the {@code maxDrainBytes} limit, {@link LineTooLargeException}
 * is thrown so that subsequent lines can be read cleanly. If the discard limit is exceeded without
 * finding a newline, an {@link IOException} is thrown and the reader enters an unrecoverable state,
 * preventing subsequent processing of an unaligned stream.
 * <p>
 * Note: A byte discard limit prevents memory exhaustion and unbounded draining on continuous
 * newline-free streams, but does not by itself prevent slow-client (Slowloris) attacks;
 * transport-level read timeouts and deadlines are separate concerns.
 */
public class BoundedLineReader implements Closeable {

    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private final InputStream in;
    private final int maxBytes;
    private final long maxDrainBytes;
    private final byte[] buffer;
    private int bufferPos = 0;
    private int bufferCount = 0;
    private boolean closed = false;
    private boolean unrecoverable = false;

    public BoundedLineReader(InputStream in, int maxBytes) {
        this(in, maxBytes, DEFAULT_BUFFER_SIZE, (long) maxBytes);
    }

    public BoundedLineReader(InputStream in, int maxBytes, int bufferSize) {
        this(in, maxBytes, bufferSize, (long) maxBytes);
    }

    public BoundedLineReader(InputStream in, int maxBytes, int bufferSize, long maxDrainBytes) {
        if (in == null) {
            throw new IllegalArgumentException("InputStream cannot be null");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        if (maxDrainBytes <= 0) {
            throw new IllegalArgumentException("maxDrainBytes must be positive");
        }
        this.in = in;
        this.maxBytes = maxBytes;
        this.buffer = new byte[bufferSize];
        this.maxDrainBytes = maxDrainBytes;
    }

    /**
     * Reads a line of text terminated by '\n' or '\r\n', or reaching EOF.
     *
     * @return the line as a UTF-8 String without line-termination characters,
     *         or {@code null} if the end of the stream has been reached without reading any bytes.
     * @throws LineTooLargeException if the line exceeds {@code maxBytes} and was successfully
     *                               drained up to the next newline or EOF within {@code maxDrainBytes}.
     * @throws IOException           if an I/O error occurs, if the stream is closed or unrecoverable,
     *                               or if draining exceeds {@code maxDrainBytes} without finding a newline.
     */
    public String readLine() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (unrecoverable) {
            throw new IOException("Stream unrecoverable: maximum drain limit exceeded");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(1024, maxBytes));
        int accumulatedBytes = 0;

        while (true) {
            if (bufferPos >= bufferCount) {
                bufferCount = in.read(buffer);
                bufferPos = 0;
                if (bufferCount == -1) {
                    if (accumulatedBytes == 0) {
                        return null;
                    }
                    return finishLine(out);
                }
            }

            while (bufferPos < bufferCount) {
                byte b = buffer[bufferPos++];
                if (b == '\n') {
                    return finishLine(out);
                }

                accumulatedBytes++;
                if (accumulatedBytes > maxBytes) {
                    drainOversizedLine();
                    throw new LineTooLargeException(
                        "Request line exceeds maximum limit of " + maxBytes + " bytes",
                        maxBytes
                    );
                }

                out.write(b);
            }
        }
    }

    private String finishLine(ByteArrayOutputStream out) {
        byte[] bytes = out.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    /**
     * Discards up to {@code maxDrainBytes} bytes from the stream until the next '\n' or EOF.
     * <p>
     * Boundary semantics:
     * <ul>
     *   <li>A newline occurring at any position up to and including {@code maxDrainBytes}
     *       is accepted, positioning the stream for subsequent reads.</li>
     *   <li>Reaching EOF while {@code drained <= maxDrainBytes} is accepted.</li>
     *   <li>If draining requires more than {@code maxDrainBytes} bytes without encountering
     *       a newline, the reader marks the stream as unrecoverable and throws an {@link IOException}.</li>
     * </ul>
     */
    private void drainOversizedLine() throws IOException {
        long drained = 0;
        while (true) {
            if (bufferPos >= bufferCount) {
                bufferCount = in.read(buffer);
                bufferPos = 0;
                if (bufferCount == -1) {
                    return;
                }
            }
            while (bufferPos < bufferCount) {
                drained++;
                if (drained > maxDrainBytes) {
                    unrecoverable = true;
                    throw new IOException(
                        "Request line exceeded maximum drain limit of " + maxDrainBytes + " bytes without newline"
                    );
                }
                if (buffer[bufferPos++] == '\n') {
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        in.close();
    }
}
