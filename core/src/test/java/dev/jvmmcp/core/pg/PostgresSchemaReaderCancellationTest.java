package dev.jvmmcp.core.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostgresSchemaReaderCancellationTest {

    private PostgresSchemaReader reader;

    @BeforeEach
    void setUp() {
        reader =
                new PostgresSchemaReader(
                        "jdbc:postgresql://localhost/jvm_mcp_cancellation", null, null);
    }

    @AfterEach
    void tearDown() {
        reader.close();
    }

    @Test
    @DisplayName(
            "executeCancellable cancels the in-flight statement when the worker is interrupted")
    void shouldCancelStatementWhenWorkerIsInterrupted() throws Exception {
        Statement statement = mock(Statement.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch statementCancelled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        doAnswer(
                        invocation -> {
                            statementCancelled.countDown();
                            return null;
                        })
                .when(statement)
                .cancel();

        Thread worker =
                new Thread(
                        () -> {
                            try {
                                reader.executeCancellable(
                                        statement,
                                        () -> {
                                            queryStarted.countDown();
                                            new CountDownLatch(1).await();
                                            return null;
                                        });
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        });
        worker.start();

        assertThat(queryStarted.await(2, TimeUnit.SECONDS)).isTrue();

        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(statementCancelled.await(1, TimeUnit.SECONDS)).isTrue();
        verify(statement).cancel();
        assertThat(failure.get()).isInstanceOf(SQLException.class);
        assertThat(worker.isInterrupted()).isTrue();
    }
}
