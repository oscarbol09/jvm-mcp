package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.HikariPoolStatistics;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class HikariMXBeanClient {

    private static final String HIKARI_POOL_DOMAIN = "com.zaxxer.hikari";

    private final MBeanServerConnection mbsc;

    public HikariMXBeanClient(MBeanServerConnection mbsc) {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null.");
        }
        this.mbsc = mbsc;
    }

            public List<HikariPoolStatistics> getPools() throws IOException {
                try {
                    Set<ObjectName> poolNames = mbsc.queryNames(
                        new ObjectName(HIKARI_POOL_DOMAIN + ":type=Pool (*)"),
                        null
                    );

                    List<HikariPoolStatistics> pools = new ArrayList<>();

                    for (ObjectName poolName : poolNames) {
                        pools.add(readPool(poolName));
                    }

                    return pools;
                } catch (Exception e) {
                    throw new IOException("Failed to query HikariCP MBeans", e);
                }
            }
            private HikariPoolStatistics readPool(ObjectName poolName) throws IOException {
                try {
                    int active = (Integer) mbsc.getAttribute(poolName, "ActiveConnections");
                    int idle = (Integer) mbsc.getAttribute(poolName, "IdleConnections");
                    int total = (Integer) mbsc.getAttribute(poolName, "TotalConnections");
                    int waiting = (Integer) mbsc.getAttribute(poolName, "ThreadsAwaitingConnection");

                    String poolNameValue = extractPoolName(poolName);

                    int maximumPoolSize = getMaximumPoolSize(poolNameValue);

                    double saturation = maximumPoolSize > 0
                        ? (double) active / maximumPoolSize
                        : 0.0;

                    return new HikariPoolStatistics(
                        poolNameValue,
                        active,
                        idle,
                        total,
                        waiting,
                        maximumPoolSize,
                        saturation
                    );
                } catch (Exception e) {
                    throw new IOException(
                        "Failed to read HikariCP pool: " + poolName,
                        e
                    );
                }
            }

            private int getMaximumPoolSize(String poolName) throws IOException {
                try {
                    ObjectName configName = new ObjectName(
                      HIKARI_POOL_DOMAIN + ":type=PoolConfig (" + poolName + ")"
                    );

                    return (Integer) mbsc.getAttribute(
                        configName,
                        "MaximumPoolSize"
                    );
                } catch (Exception e) {
                    throw new IOException(
                        "Failed to read maximum pool size for HikariCP pool: " + poolName,
                        e
                    );
                }
            }
            private String extractPoolName(ObjectName objectName) {
                String value = objectName.toString();

                String prefix = HIKARI_POOL_DOMAIN + ":type=Pool (";

                if (value.startsWith(prefix) && value.endsWith(")")) {
                    return value.substring(
                        prefix.length(),
                        value.length() - 1
                    );
                }

            return value;
            }

}