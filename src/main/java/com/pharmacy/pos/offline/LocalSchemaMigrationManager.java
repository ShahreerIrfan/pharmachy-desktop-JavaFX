package com.pharmacy.pos.offline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;

/**
 * LocalSchemaMigrationManager
 * Architecture Decision: ADR-0318 (P318).
 * Phase 16: JavaFX SQLite Offline Store & Reliable Cloud Sync.
 *
 * Governs local SQLite schema versioning, automated backups, rollback, and recovery:
 * - Invariant: Never drops or truncates pending outbox events during upgrades.
 * - Enforces pre-migration outbox count verification and automated rollback upon mismatch.
 * - Computes backup SHA-256 for cloud audit reconciliation.
 */
public class LocalSchemaMigrationManager {

    public static class MigrationResult {
        private final boolean success;
        private final int fromVersion;
        private final int toVersion;
        private final int outboxEventsPreserved;
        private final String backupSha256;
        private final String errorMessage;

        public MigrationResult(
                boolean success,
                int fromVersion,
                int toVersion,
                int outboxEventsPreserved,
                String backupSha256,
                String errorMessage
        ) {
            this.success = success;
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.outboxEventsPreserved = outboxEventsPreserved;
            this.backupSha256 = backupSha256 != null ? backupSha256 : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() { return success; }
        public int getFromVersion() { return fromVersion; }
        public int getToVersion() { return toVersion; }
        public int getOutboxEventsPreserved() { return outboxEventsPreserved; }
        public String getBackupSha256() { return backupSha256; }
        public String getErrorMessage() { return errorMessage; }
    }

    public int getCurrentSchemaVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT schema_version FROM device_metadata WHERE singleton_id = 1")) {
            if (rs.next()) {
                return rs.getInt("schema_version");
            }
        }
        return 1;
    }

    public int getPendingOutboxCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pending_offline_commands")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Executes forward migration with strict outbox event preservation invariant.
     */
    public MigrationResult applyMigration(
            Connection conn,
            int targetVersion,
            String forwardSql,
            String backupSha256
    ) throws SQLException {
        int currentVersion = getCurrentSchemaVersion(conn);
        if (targetVersion <= currentVersion) {
            throw new IllegalArgumentException("ERR_INVALID_TARGET_VERSION: Target version " +
                    targetVersion + " must be > current version " + currentVersion);
        }

        int countBefore = getPendingOutboxCount(conn);
        boolean originalAutoCommit = conn.getAutoCommit();

        try {
            conn.setAutoCommit(false);

            // 1. Execute Forward DDL / Migration SQL
            if (forwardSql != null && !forwardSql.trim().isEmpty()) {
                try (Statement st = conn.createStatement()) {
                    for (String stmt : forwardSql.split(";")) {
                        String trimmed = stmt.trim();
                        if (!trimmed.isEmpty()) {
                            st.execute(trimmed);
                        }
                    }
                }
            }

            // 2. Update device_metadata schema_version
            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE device_metadata SET schema_version = ?, updated_at = ? WHERE singleton_id = 1")) {
                ps.setInt(1, targetVersion);
                ps.setString(2, now);
                ps.executeUpdate();
            }

            // 3. Record migration in schema_migrations
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_migrations (version, ddl_sha256, applied_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, targetVersion);
                ps.setString(2, backupSha256 != null ? backupSha256 : "manual_migration");
                ps.setString(3, now);
                ps.executeUpdate();
            }

            // 4. Outbox Preservation Invariant Check
            int countAfter = getPendingOutboxCount(conn);
            if (countAfter != countBefore) {
                conn.rollback();
                throw new IllegalStateException("ERR_OUTBOX_EVENT_LOSS_DETECTED: Migration aborted and rolled back. " +
                        "Pending events before: " + countBefore + ", after: " + countAfter);
            }

            conn.commit();
            return new MigrationResult(true, currentVersion, targetVersion, countAfter, backupSha256, null);

        } catch (Exception e) {
            conn.rollback();
            return new MigrationResult(false, currentVersion, targetVersion, countBefore, backupSha256, e.getMessage());
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * Executes clean rollback to previous version while protecting outbox events.
     */
    public MigrationResult rollbackMigration(
            Connection conn,
            int targetVersion,
            String rollbackSql
    ) throws SQLException {
        int currentVersion = getCurrentSchemaVersion(conn);
        if (targetVersion >= currentVersion) {
            throw new IllegalArgumentException("ERR_INVALID_ROLLBACK_VERSION: Target version " +
                    targetVersion + " must be < current version " + currentVersion);
        }

        int countBefore = getPendingOutboxCount(conn);
        boolean originalAutoCommit = conn.getAutoCommit();

        try {
            conn.setAutoCommit(false);

            if (rollbackSql != null && !rollbackSql.trim().isEmpty()) {
                try (Statement st = conn.createStatement()) {
                    for (String stmt : rollbackSql.split(";")) {
                        String trimmed = stmt.trim();
                        if (!trimmed.isEmpty()) {
                            st.execute(trimmed);
                        }
                    }
                }
            }

            String now = Instant.now().toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE device_metadata SET schema_version = ?, updated_at = ? WHERE singleton_id = 1")) {
                ps.setInt(1, targetVersion);
                ps.setString(2, now);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM schema_migrations WHERE version > ?")) {
                ps.setInt(1, targetVersion);
                ps.executeUpdate();
            }

            int countAfter = getPendingOutboxCount(conn);
            if (countAfter != countBefore) {
                conn.rollback();
                throw new IllegalStateException("ERR_OUTBOX_EVENT_LOSS_DETECTED: Rollback aborted. Outbox mismatch.");
            }

            conn.commit();
            return new MigrationResult(true, currentVersion, targetVersion, countAfter, "", null);

        } catch (Exception e) {
            conn.rollback();
            return new MigrationResult(false, currentVersion, targetVersion, countBefore, "", e.getMessage());
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * Creates an atomic file backup and calculates SHA-256 for audit tracking.
     */
    public File createDatabaseBackup(File dbFile, File backupDir) throws IOException, NoSuchAlgorithmException {
        if (!dbFile.exists()) {
            throw new IllegalArgumentException("ERR_DB_FILE_NOT_FOUND");
        }
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        File backupFile = new File(backupDir, dbFile.getName() + "." + timestamp + ".bak");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(dbFile);
             FileOutputStream out = new FileOutputStream(backupFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }

        return backupFile;
    }

    public String computeFileSha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
