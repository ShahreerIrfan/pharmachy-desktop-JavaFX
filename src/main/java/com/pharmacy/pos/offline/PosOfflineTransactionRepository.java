package com.pharmacy.pos.offline;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Architecture Decision: ADR-0238 (P238).
 * Atomic Offline POS SQLite Transaction Repository & Durable Outbox Engine.
 * 
 * Guarantees that local sales, item lines, stock allocations, and outbox synchronization
 * events are committed within a single, strictly atomic SQLite transaction.
 */
public class PosOfflineTransactionRepository implements AutoCloseable {

    private final Connection connection;

    public PosOfflineTransactionRepository(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection, "Database connection cannot be null");
        initSchema();
    }

    public static PosOfflineTransactionRepository inMemory() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        return new PosOfflineTransactionRepository(conn);
    }

    public synchronized void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_batch_stock (
                    batch_id TEXT PRIMARY KEY,
                    product_id TEXT NOT NULL,
                    batch_number TEXT NOT NULL,
                    expiry_date TEXT NOT NULL,
                    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
                    unit_cost_bdt TEXT NOT NULL,
                    mrp_bdt TEXT NOT NULL
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_sale_orders (
                    sale_id TEXT PRIMARY KEY,
                    order_number TEXT NOT NULL UNIQUE,
                    tenant_id TEXT NOT NULL,
                    branch_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    cashier_id TEXT NOT NULL,
                    subtotal_bdt TEXT NOT NULL,
                    discount_bdt TEXT NOT NULL,
                    tax_bdt TEXT NOT NULL,
                    grand_total_bdt TEXT NOT NULL,
                    paid_bdt TEXT NOT NULL,
                    due_bdt TEXT NOT NULL,
                    payment_method TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_sale_lines (
                    line_id TEXT PRIMARY KEY,
                    sale_id TEXT NOT NULL,
                    product_id TEXT NOT NULL,
                    batch_id TEXT NOT NULL,
                    batch_number TEXT NOT NULL,
                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                    unit_price_bdt TEXT NOT NULL,
                    line_total_bdt TEXT NOT NULL,
                    FOREIGN KEY (sale_id) REFERENCES offline_sale_orders(sale_id)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_stock_allocations (
                    allocation_id TEXT PRIMARY KEY,
                    sale_id TEXT NOT NULL,
                    line_id TEXT NOT NULL,
                    batch_id TEXT NOT NULL,
                    quantity_allocated INTEGER NOT NULL CHECK (quantity_allocated > 0),
                    allocated_at TEXT NOT NULL,
                    FOREIGN KEY (sale_id) REFERENCES offline_sale_orders(sale_id)
                );
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS offline_outbox_events (
                    event_id TEXT PRIMARY KEY,
                    event_type TEXT NOT NULL,
                    aggregate_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    synced_at TEXT
                );
                """);
        }
    }

    public static class OfflineSaleLineCmd {
        public final String lineId;
        public final String productId;
        public final String batchId;
        public final String batchNumber;
        public final int quantity;
        public final BigDecimal unitPriceBdt;
        public final BigDecimal lineTotalBdt;

        public OfflineSaleLineCmd(String lineId, String productId, String batchId, String batchNumber,
                                  int quantity, BigDecimal unitPriceBdt, BigDecimal lineTotalBdt) {
            this.lineId = lineId != null ? lineId : "line-" + UUID.randomUUID();
            this.productId = productId;
            this.batchId = batchId;
            this.batchNumber = batchNumber;
            this.quantity = quantity;
            this.unitPriceBdt = unitPriceBdt;
            this.lineTotalBdt = lineTotalBdt != null ? lineTotalBdt : unitPriceBdt.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static class OfflineSaleCommand {
        public final String saleId;
        public final String orderNumber;
        public final String tenantId;
        public final String branchId;
        public final String deviceId;
        public final String cashierId;
        public final BigDecimal grandTotalBdt;
        public final BigDecimal paidBdt;
        public final BigDecimal dueBdt;
        public final String paymentMethod;
        public final List<OfflineSaleLineCmd> lines;

        public OfflineSaleCommand(String saleId, String orderNumber, String tenantId, String branchId,
                                  String deviceId, String cashierId, BigDecimal grandTotalBdt, BigDecimal paidBdt,
                                  BigDecimal dueBdt, String paymentMethod, List<OfflineSaleLineCmd> lines) {
            this.saleId = saleId != null ? saleId : "sale-off-" + UUID.randomUUID();
            this.orderNumber = orderNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.deviceId = deviceId;
            this.cashierId = cashierId;
            this.grandTotalBdt = grandTotalBdt;
            this.paidBdt = paidBdt != null ? paidBdt : grandTotalBdt;
            this.dueBdt = dueBdt != null ? dueBdt : BigDecimal.ZERO;
            this.paymentMethod = paymentMethod != null ? paymentMethod : "CASH";
            this.lines = lines != null ? lines : Collections.emptyList();
        }
    }

    public static class OutboxEventDto {
        public final String eventId;
        public final String eventType;
        public final String aggregateId;
        public final String payloadJson;
        public final String status;
        public final String createdAt;

        public OutboxEventDto(String eventId, String eventType, String aggregateId,
                              String payloadJson, String status, String createdAt) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.aggregateId = aggregateId;
            this.payloadJson = payloadJson;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    public static class InsufficientLocalStockException extends RuntimeException {
        public InsufficientLocalStockException(String message) {
            super(message);
        }
    }

    public synchronized void seedBatchStock(String batchId, String productId, String batchNumber,
                                            String expiryDate, int quantity, BigDecimal unitCost, BigDecimal mrp) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO offline_batch_stock
            (batch_id, product_id, batch_number, expiry_date, available_quantity, unit_cost_bdt, mrp_bdt)
            VALUES (?, ?, ?, ?, ?, ?, ?);
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, batchId);
            ps.setString(2, productId);
            ps.setString(3, batchNumber);
            ps.setString(4, expiryDate);
            ps.setInt(5, quantity);
            ps.setString(6, unitCost.toPlainString());
            ps.setString(7, mrp.toPlainString());
            ps.executeUpdate();
        }
    }

    public synchronized int getAvailableStock(String batchId) throws SQLException {
        String sql = "SELECT available_quantity FROM offline_batch_stock WHERE batch_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    /**
     * Atomically commits an offline sale, its item lines, local stock deductions,
     * allocation audits, and durable outbox event in a single SQLite transaction.
     */
    public synchronized String commitOfflineSaleAtomic(OfflineSaleCommand cmd) throws SQLException {
        if (cmd.lines.isEmpty()) {
            throw new IllegalArgumentException("Offline sale must contain at least one line item.");
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            String nowIso = Instant.now().toString();

            // 1. Verify stock availability for all lines before deduction
            for (OfflineSaleLineCmd line : cmd.lines) {
                int currentStock = getAvailableStock(line.batchId);
                if (currentStock < line.quantity) {
                    throw new InsufficientLocalStockException(
                        String.format("Insufficient local batch stock for batch %s (%s). Available: %d, Requested: %d",
                            line.batchId, line.batchNumber, Math.max(0, currentStock), line.quantity)
                    );
                }
            }

            // 2. Insert Sale Order Header
            String insertSaleSql = """
                INSERT INTO offline_sale_orders
                (sale_id, order_number, tenant_id, branch_id, device_id, cashier_id,
                 subtotal_bdt, discount_bdt, tax_bdt, grand_total_bdt, paid_bdt, due_bdt,
                 payment_method, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;
            try (PreparedStatement ps = connection.prepareStatement(insertSaleSql)) {
                ps.setString(1, cmd.saleId);
                ps.setString(2, cmd.orderNumber);
                ps.setString(3, cmd.tenantId);
                ps.setString(4, cmd.branchId);
                ps.setString(5, cmd.deviceId);
                ps.setString(6, cmd.cashierId);
                ps.setString(7, cmd.grandTotalBdt.toPlainString());
                ps.setString(8, "0.00");
                ps.setString(9, "0.00");
                ps.setString(10, cmd.grandTotalBdt.toPlainString());
                ps.setString(11, cmd.paidBdt.toPlainString());
                ps.setString(12, cmd.dueBdt.toPlainString());
                ps.setString(13, cmd.paymentMethod);
                ps.setString(14, "COMMITTED");
                ps.setString(15, nowIso);
                ps.executeUpdate();
            }

            // 3. Insert Sale Lines & Deduct Stock & Record Allocations
            String updateStockSql = "UPDATE offline_batch_stock SET available_quantity = available_quantity - ? WHERE batch_id = ?;";
            String insertLineSql = """
                INSERT INTO offline_sale_lines
                (line_id, sale_id, product_id, batch_id, batch_number, quantity, unit_price_bdt, line_total_bdt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """;
            String insertAllocSql = """
                INSERT INTO offline_stock_allocations
                (allocation_id, sale_id, line_id, batch_id, quantity_allocated, allocated_at)
                VALUES (?, ?, ?, ?, ?, ?);
                """;

            try (PreparedStatement psStock = connection.prepareStatement(updateStockSql);
                 PreparedStatement psLine = connection.prepareStatement(insertLineSql);
                 PreparedStatement psAlloc = connection.prepareStatement(insertAllocSql)) {

                for (OfflineSaleLineCmd line : cmd.lines) {
                    // Stock deduction
                    psStock.setInt(1, line.quantity);
                    psStock.setString(2, line.batchId);
                    psStock.executeUpdate();

                    // Line insert
                    psLine.setString(1, line.lineId);
                    psLine.setString(2, cmd.saleId);
                    psLine.setString(3, line.productId);
                    psLine.setString(4, line.batchId);
                    psLine.setString(5, line.batchNumber);
                    psLine.setInt(6, line.quantity);
                    psLine.setString(7, line.unitPriceBdt.toPlainString());
                    psLine.setString(8, line.lineTotalBdt.toPlainString());
                    psLine.executeUpdate();

                    // Allocation insert
                    psAlloc.setString(1, "alloc-" + UUID.randomUUID());
                    psAlloc.setString(2, cmd.saleId);
                    psAlloc.setString(3, line.lineId);
                    psAlloc.setString(4, line.batchId);
                    psAlloc.setInt(5, line.quantity);
                    psAlloc.setString(6, nowIso);
                    psAlloc.executeUpdate();
                }
            }

            // 4. Insert Durable Outbox Event
            String outboxEventId = "evt-" + UUID.randomUUID();
            String payloadJson = String.format(
                "{\"sale_id\":\"%s\",\"order_number\":\"%s\",\"tenant_id\":\"%s\",\"branch_id\":\"%s\"," +
                "\"grand_total_bdt\":\"%s\",\"lines_count\":%d,\"is_offline_sync\":true}",
                cmd.saleId, cmd.orderNumber, cmd.tenantId, cmd.branchId, cmd.grandTotalBdt.toPlainString(), cmd.lines.size()
            );

            String insertOutboxSql = """
                INSERT INTO offline_outbox_events
                (event_id, event_type, aggregate_id, payload_json, status, retry_count, created_at)
                VALUES (?, ?, ?, ?, ?, 0, ?);
                """;
            try (PreparedStatement psOutbox = connection.prepareStatement(insertOutboxSql)) {
                psOutbox.setString(1, outboxEventId);
                psOutbox.setString(2, "POS_SALE_COMMITTED");
                psOutbox.setString(3, cmd.saleId);
                psOutbox.setString(4, payloadJson);
                psOutbox.setString(5, "PENDING");
                psOutbox.setString(6, nowIso);
                psOutbox.executeUpdate();
            }

            // 5. Commit atomic transaction
            connection.commit();
            return outboxEventId;
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public synchronized List<OutboxEventDto> getPendingOutboxEvents() throws SQLException {
        String sql = "SELECT event_id, event_type, aggregate_id, payload_json, status, created_at FROM offline_outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC;";
        List<OutboxEventDto> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new OutboxEventDto(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6)
                ));
            }
        }
        return list;
    }

    public synchronized void markOutboxSynced(String eventId) throws SQLException {
        String sql = "UPDATE offline_outbox_events SET status = 'SYNCED', synced_at = ? WHERE event_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventId);
            ps.executeUpdate();
        }
    }

    public synchronized boolean hasSaleOrder(String saleId) throws SQLException {
        String sql = "SELECT 1 FROM offline_sale_orders WHERE sale_id = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, saleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
