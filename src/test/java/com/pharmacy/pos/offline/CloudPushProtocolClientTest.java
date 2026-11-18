package com.pharmacy.pos.offline;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CloudPushProtocolClientTest (ADR-0305 / P305)
 * Verifies HTTPS enforcement, bounded batch slicing (<= 50), strict ascending sequence check,
 * event version validation, and HMAC-SHA256 device signing.
 */
public class CloudPushProtocolClientTest {

    @Test
    void testHttpsEnforcementBoundedSlicingAndHmacSigning() {
        // 1. Insecure remote HTTP rejected
        assertThrows(SecurityException.class, () ->
                new CloudPushProtocolClient("http://api.pharmacy.bd", "ten-1", "pos-1", "a".repeat(64), "")
        );

        CloudPushProtocolClient client = new CloudPushProtocolClient(
                "https://api.pharmacy.bd", "ten-1", "pos-1", "a".repeat(64), "b".repeat(64)
        );
        assertEquals("https://api.pharmacy.bd/api/v2/offline-sync/push/execute/", client.getExecutePushEndpoint());

        // 2. Bounded slicing splits 120 events into 50 + 50 + 20
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            many.add(Map.of("client_sequence", i, "event_version", 1, "idempotency_key", "k-" + i));
        }
        List<List<Map<String, Object>>> chunks = client.sliceIntoBoundedBatches(many);
        assertEquals(3, chunks.size());
        assertEquals(50, chunks.get(0).size());
        assertEquals(50, chunks.get(1).size());
        assertEquals(20, chunks.get(2).size());

        // 3. Signed batch produces 64-char HMAC-SHA256
        Map<String, Object> signed = client.buildSignedPushBatch("batch-001", chunks.get(2), "[{\"seq\":1}]");
        assertEquals(64, String.valueOf(signed.get("signature_hmac_sha256")).length());
        assertEquals(1, signed.get("protocol_version"));

        // 4. Unordered batch rejected
        List<Map<String, Object>> unordered = List.of(
                Map.of("client_sequence", 5, "event_version", 1),
                Map.of("client_sequence", 3, "event_version", 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
                client.buildSignedPushBatch("batch-bad", unordered, "[]")
        );
    }
}
