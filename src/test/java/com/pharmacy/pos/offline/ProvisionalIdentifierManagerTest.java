package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProvisionalIdentifierManagerTest (ADR-0315 / P315)
 * Tests client-side issuance of provisional UUIDs, SQLite persistence in local_provisional_mappings,
 * and transparent bidirectional resolution for barcode scanning and receipt reprints.
 */
public class ProvisionalIdentifierManagerTest {

    @Test
    void testProvisionalIdIssuanceAndBidirectionalResolution() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DeviceLocalDatabaseSchema.initializeAndBind(
                    conn, "pos-pim-1", "ten-pim-1", "br-pim-1", "d".repeat(64), "CONTROLLED_RECONCILIATION"
            );

            // 1. Issue provisional IDs
            String provInvoiceId = ProvisionalIdentifierManager.issueProvisionalId(
                    ProvisionalIdentifierManager.ProvisionalEntityType.INVOICE
            );
            assertTrue(provInvoiceId.startsWith("PROV-INVO-"));

            String provCustId = ProvisionalIdentifierManager.issueProvisionalId(
                    ProvisionalIdentifierManager.ProvisionalEntityType.CUSTOMER
            );
            assertTrue(provCustId.startsWith("PROV-CUST-"));

            // 2. Persist server-reconciled mappings
            String canonInvoiceId = "CAN-INV-202611-00889";
            ProvisionalIdentifierManager.storeProvisionalMapping(
                    conn, "ten-pim-1", "br-pim-1",
                    ProvisionalIdentifierManager.ProvisionalEntityType.INVOICE,
                    provInvoiceId, canonInvoiceId, "idem-inv-1"
            );

            // 3. Bidirectional Lookups
            var lookedUpCanon = ProvisionalIdentifierManager.lookupCanonical(
                    conn, "ten-pim-1",
                    ProvisionalIdentifierManager.ProvisionalEntityType.INVOICE,
                    provInvoiceId
            );
            assertTrue(lookedUpCanon.isPresent());
            assertEquals(canonInvoiceId, lookedUpCanon.get());

            var lookedUpProv = ProvisionalIdentifierManager.lookupProvisional(
                    conn, "ten-pim-1",
                    ProvisionalIdentifierManager.ProvisionalEntityType.INVOICE,
                    canonInvoiceId
            );
            assertTrue(lookedUpProv.isPresent());
            assertEquals(provInvoiceId, lookedUpProv.get());

            // 4. ResolveAny with either ID
            var resFromProv = ProvisionalIdentifierManager.resolveAny(conn, "ten-pim-1", provInvoiceId);
            assertTrue(resFromProv.isPresent());
            assertEquals(canonInvoiceId, resFromProv.get().canonicalId);

            var resFromCanon = ProvisionalIdentifierManager.resolveAny(conn, "ten-pim-1", canonInvoiceId);
            assertTrue(resFromCanon.isPresent());
            assertEquals(provInvoiceId, resFromCanon.get().provisionalId);

            // Non-existent ID returns empty
            var resEmpty = ProvisionalIdentifierManager.resolveAny(conn, "ten-pim-1", "UNKNOWN-ID");
            assertTrue(resEmpty.isEmpty());
        }
    }
}
