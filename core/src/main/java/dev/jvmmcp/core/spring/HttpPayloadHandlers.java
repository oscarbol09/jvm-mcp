package dev.jvmmcp.core.spring;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Shared size-limited HTTP response handling for Spring Actuator requests.
 */
final class HttpPayloadHandlers {

    private HttpPayloadHandlers() {}

    static HttpResponse.BodyHandler<String> limitingStringHandler(long maxBytes) {
        return responseInfo -> {
            responseInfo.headers().firstValueAsLong("Content-Length").ifPresent(len -> {
                if (len > maxBytes) {
                    throw new IllegalArgumentException("Content-Length " + len + " exceeds limit " + maxBytes);
                }
            });

            String encoding = responseInfo.headers().firstValue("Content-Encoding").orElse(null);
            HttpResponse.BodySubscriber<byte[]> byteSubscriber = HttpResponse.BodySubscribers.ofByteArray();
            
            return new HttpResponse.BodySubscriber<String>() {
                private long totalReceived = 0;
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    byteSubscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(List<ByteBuffer> item) {
                    long batchSize = 0;
                    for (ByteBuffer b : item) {
                        batchSize += b.remaining();
                    }
                    totalReceived += batchSize;

                    if (totalReceived > maxBytes) {
                        subscription.cancel();
                        byteSubscriber.onError(new IOException("Response size exceeded maximum limit of " + maxBytes + " bytes"));
                        return;
                    }
                    byteSubscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    byteSubscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    byteSubscriber.onComplete();
                }

                @Override
                public CompletionStage<String> getBody() {
                    return byteSubscriber.getBody().thenApply(bytes -> {
                        try {
                            return decodeResponseBody(bytes, encoding, maxBytes);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            };
        };
    }

    private static String decodeResponseBody(byte[] bytes, String encoding, long maxUncompressedBytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        InputStream is = new ByteArrayInputStream(bytes);
        if ("gzip".equalsIgnoreCase(encoding)) {
            is = new GZIPInputStream(is);
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            is = new InflaterInputStream(is);
        }
        try (InputStream in = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new IOException("Decompressed response size exceeded maximum limit of " + maxUncompressedBytes + " bytes");
                }
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

}
