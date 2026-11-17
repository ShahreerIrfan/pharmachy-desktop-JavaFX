package com.pharmacy.pos.prescriptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for P245 RxItemMatchingClient (JavaFX Desktop).
 */
public class RxItemMatchingClientTest {

    @Test
    public void testExactMatchAndAuthorizedQuantity() {
        RxItemMatchingClient.MatchedLineDto res = RxItemMatchingClient.matchCartLine(
                "prod-mox-01",
                "Moxacil 500mg",
                15,
                "prod-mox-01",
                "Moxacil 500mg",
                10,
                true
        );

        assertTrue(res.isSuccess());
        assertEquals(RxItemMatchingClient.MatchResultStatus.MATCHED, res.getMatchStatus());
        assertEquals(5, res.getRemainingQuantity());
        assertNull(res.getError());
    }

    @Test
    public void testAutomatedSubstitutionProhibited() {
        // Attempting to match Ciprofloxacin with Moxacil
        RxItemMatchingClient.MatchedLineDto res = RxItemMatchingClient.matchCartLine(
                "prod-mox-01",
                "Moxacil 500mg",
                15,
                "prod-cipro-01",
                "Ciprocin 500mg",
                10,
                true
        );

        assertFalse(res.isSuccess());
        assertEquals(RxItemMatchingClient.MatchResultStatus.SUBSTITUTION_PROHIBITED, res.getMatchStatus());
        assertNotNull(res.getError());
        assertTrue(res.getError().contains("Automated treatment substitution is prohibited"));
        assertEquals("অননুমোদিত ব্র্যান্ড প্রতিস্থাপন নিষিদ্ধ", res.getMatchStatus().getLocalized("bn"));
    }

    @Test
    public void testOverQuantityDetected() {
        RxItemMatchingClient.MatchedLineDto res = RxItemMatchingClient.matchCartLine(
                "prod-mox-01",
                "Moxacil 500mg",
                5,
                "prod-mox-01",
                "Moxacil 500mg",
                10,
                true
        );

        assertFalse(res.isSuccess());
        assertEquals(RxItemMatchingClient.MatchResultStatus.EXCEEDS_QUANTITY, res.getMatchStatus());
        assertTrue(res.getError().contains("exceeds remaining authorized quota"));
    }

    @Test
    public void testOtcExemptItem() {
        RxItemMatchingClient.MatchedLineDto res = RxItemMatchingClient.matchCartLine(
                "prod-any",
                "None",
                0,
                "prod-napa-01",
                "Napa 500mg",
                20,
                false // OTC exempt
        );

        assertTrue(res.isSuccess());
        assertEquals(RxItemMatchingClient.MatchResultStatus.OTC_EXEMPT, res.getMatchStatus());
        assertEquals(20, res.getRemainingQuantity());
    }
}

