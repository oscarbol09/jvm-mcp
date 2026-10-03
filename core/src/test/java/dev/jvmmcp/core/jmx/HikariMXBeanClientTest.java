
package dev.jvmmcp.core.jmx;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jvmmcp.core.model.HikariPoolStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HikariMXBeanClientTest {

    private JmxConnectionManager connectionManager;
    private HikariDataSource dataSource;
    private HikariDataSource secondDataSource;
    private HikariMXBeanClient hikariClient;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("TestHikariPool");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setJdbcUrl("jdbc:h2:mem:testdb");
        config.setRegisterMbeans(true);

        dataSource = new HikariDataSource(config);

        HikariConfig secondConfig = new HikariConfig();
        secondConfig.setPoolName("SecondTestHikariPool");
        secondConfig.setMaximumPoolSize(10);
        secondConfig.setMinimumIdle(1);
        secondConfig.setJdbcUrl("jdbc:h2:mem:testdb2");
        secondConfig.setRegisterMbeans(true);

        secondDataSource = new HikariDataSource(secondConfig);

        connectionManager = JmxConnectionManager.connectLocal();
        hikariClient = new HikariMXBeanClient(
            connectionManager.getMBeanServerConnection()
        );
    }

    @AfterEach
    void tearDown() {
        if (secondDataSource != null) {
            secondDataSource.close();
        }

        if (dataSource != null) {
            dataSource.close();
        }

        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void shouldDiscoverHikariPool() throws IOException {
        List<HikariPoolStatistics> pools = hikariClient.getPools();

        assertThat(pools)
            .anyMatch(pool -> pool.poolName().equals("TestHikariPool"));
    }

    @Test
    void shouldDiscoverMultipleHikariPools() throws IOException {
        List<HikariPoolStatistics> pools = hikariClient.getPools();

        assertThat(pools)
            .extracting(HikariPoolStatistics::poolName)
            .contains("TestHikariPool", "SecondTestHikariPool");
    }

    @Test
    void shouldReadPoolStatistics() throws IOException {
        List<HikariPoolStatistics> pools = hikariClient.getPools();

        HikariPoolStatistics pool = pools.stream()
            .filter(p -> p.poolName().equals("TestHikariPool"))
            .findFirst()
            .orElseThrow();

        assertThat(pool.activeConnections()).isGreaterThanOrEqualTo(0);
        assertThat(pool.idleConnections()).isGreaterThanOrEqualTo(0);
        assertThat(pool.totalConnections()).isGreaterThanOrEqualTo(0);
        assertThat(pool.threadsAwaitingConnection()).isGreaterThanOrEqualTo(0);
        assertThat(pool.maximumPoolSize()).isEqualTo(5);
    }

    @Test
    void shouldCalculateSaturationRatio() throws IOException {
        List<HikariPoolStatistics> pools = hikariClient.getPools();

        HikariPoolStatistics pool = pools.stream()
            .filter(p -> p.poolName().equals("TestHikariPool"))
            .findFirst()
            .orElseThrow();

        double expected =
            (double) pool.activeConnections() / pool.maximumPoolSize();

        assertThat(pool.saturationRatio()).isEqualTo(expected);
    }
}

