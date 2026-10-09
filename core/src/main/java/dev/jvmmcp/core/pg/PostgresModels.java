package dev.jvmmcp.core.pg;

import java.util.List;

public final class PostgresModels {

    private PostgresModels() {
        // Prevent instantiation
    }

    public record TableInfo(
            String tableName,
            long estimatedRowCount,
            int columnCount,
            long sizeBytes) {
    }

    public record IndexInfo(
            String tableName,
            String indexName,
            String indexDef,
            long sizeBytes,
            boolean isUnique,
            boolean isPrimary) {
    }

    public record ForeignKeyInfo(
            String tableName,
            String constraintName,
            String sourceColumn,
            String foreignTableName,
            String foreignColumn) {
    }

    public record SchemaInfo(
            String schemaName,
            List<TableInfo> tables,
            List<IndexInfo> indexes,
            List<ForeignKeyInfo> foreignKeys) {
    }

    public record MissingIndexCandidate(
            String tableName,
            long seqScanCount,
            long seqTupRead,
            long idxScanCount,
            long idxTupFetch) {
    }

    public record MissingIndexAnalysis(
            List<MissingIndexCandidate> candidates) {
    }

    public record SlowQueryInfo(
            String query,
            long calls,
            double meanTimeMs,
            double maxTimeMs,
            long rows) {
    }

    public record SlowQueryAnalysis(
            boolean pgStatStatementsAvailable,
            List<SlowQueryInfo> slowQueries) {
    }
}
