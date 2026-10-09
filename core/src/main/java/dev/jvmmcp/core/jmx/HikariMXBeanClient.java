package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.HikariPoolHealth;
import dev.jvmmcp.core.model.HikariPoolStatistics;
import dev.jvmmcp.core.port.HikariDiagnosticPort;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class HikariMXBeanClient implements HikariDiagnosticPort {

    private static final String HIKARI_POOL_DOMAIN = "com.zaxxer.hikari";

    private final MBeanServerConnection mbsc;

    public HikariMXBeanClient(MBeanServerConnection mbsc) {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null.");
        }
        this.mbsc = mbsc;
    }

    @Override
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
            throw new IOException("Failed to query HikariCP MBeans. Ensure spring.datasource.hikari.register-mbeans=true is set.", e);
        }
    }

    HikariPoolStatistics readPool(ObjectName poolName) throws IOException {
        try {
            int active = (Integer) mbsc.getAttribute(poolName, "ActiveConnections");
            int idle = (Integer) mbsc.getAttribute(poolName, "IdleConnections");
            int total = (Integer) mbsc.getAttribute(poolName, "TotalConnections");
            int waiting = (Integer) mbsc.getAttribute(poolName, "ThreadsAwaitingConnection");

            String poolNameValue = extractPoolName(poolName);

            int maximumPoolSize = -1;
            try {
                maximumPoolSize = getMaximumPoolSize(poolName);
            } catch (Exception ignored) {
                // If PoolConfig MBean is missing, don't fail the entire stat reading
            }

            double saturation = -1.0;
            if (maximumPoolSize > 0) {
                saturation = (double) active / maximumPoolSize;
            }

            HikariPoolHealth health = analyzeHealth(poolNameValue, active, waiting, maximumPoolSize, saturation);

            return new HikariPoolStatistics(
                poolNameValue,
                active,
                idle,
                total,
                waiting,
                maximumPoolSize,
                saturation,
                health
            );
        } catch (Exception e) {
            throw new IOException("Failed to read HikariCP pool: " + poolName, e);
        }
    }

    private HikariPoolHealth analyzeHealth(String poolName, int active, int waiting, int max, double saturation) {
        String status;
        String recommendation;

        if (waiting > 0 && active == max && max > 0) {
            status = "EXHAUSTED";
            recommendation = "CRITICAL: True Connection Starvation detected. Pool is fully active and " + waiting + " thread(s) are blocked. Investigate connection leaks or increase maximumPoolSize.";
        } else if (waiting > 0) {
            status = "DEGRADED";
            recommendation = "WARNING: Threads awaiting connection (" + waiting + "), but pool is not fully active. This indicates CPU starvation, OS scheduling issues, or a severe GC pause (Clock Leap) rather than DB pool exhaustion.";
        } else if (saturation >= 0.90) {
            status = "DEGRADED";
            recommendation = "WARNING: Pool saturation is at " + Math.round(saturation * 100) + "%. High risk of starvation during traffic spikes.";
        } else {
            status = "HEALTHY";
            recommendation = "Pool is healthy. Saturation: " + (saturation >= 0 ? Math.round(saturation * 100) + "%" : "Unknown");
        }

        return new HikariPoolHealth(poolName, status, recommendation);
    }

    int getMaximumPoolSize(ObjectName poolObjectName) throws IOException {
        try {
            String configNameStr = poolObjectName.toString().replace("type=Pool", "type=PoolConfig");
            ObjectName configName = new ObjectName(configNameStr);
            return (Integer) mbsc.getAttribute(configName, "MaximumPoolSize");
        } catch (Exception e) {
            throw new IOException("Failed to read maximum pool size from config MBean", e);
        }
    }

    String extractPoolName(ObjectName objectName) {
        String nameValue = objectName.getKeyProperty("name");
        if (nameValue != null) {
            // JMX 2.0 format: type=Pool,name=MyPoolName
            // If the name is quoted, unquote it
            if (nameValue.startsWith("\"") && nameValue.endsWith("\"")) {
                return nameValue.substring(1, nameValue.length() - 1);
            }
            return nameValue;
        }

        // Legacy format: type=Pool (MyPoolName)
        String typeValue = objectName.getKeyProperty("type");
        if (typeValue != null && typeValue.startsWith("Pool (") && typeValue.endsWith(")")) {
            return typeValue.substring(6, typeValue.length() - 1);
        }
        
        // Fallback
        return objectName.toString();
    }

    @Override
    public void close() throws Exception {
    }
}
