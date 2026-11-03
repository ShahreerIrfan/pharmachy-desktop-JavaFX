package com.pharmacy.pos.qa;

import com.pharmacy.pos.payload.PosFlexibleMedicinePayloadClient;

import java.util.*;

/**
 * PosDosageFormQaFixtureClient
 * Architecture Decision: ADR-0160 (P160).
 * Phase 08: Specialized Item & Multi-Tier Packaging Formulation Editors.
 *
 * Client-side QA fixture execution harness for JavaFX Desktop POS.
 * Validates canonical positive fixtures for all 11 dosage forms and
 * confirms strict rejection on negative error test cases.
 */
public class PosDosageFormQaFixtureClient {

    private final PosFlexibleMedicinePayloadClient payloadClient;

    public PosDosageFormQaFixtureClient() {
        this.payloadClient = new PosFlexibleMedicinePayloadClient();
    }

    public PosDosageFormQaFixtureClient(PosFlexibleMedicinePayloadClient payloadClient) {
        this.payloadClient = payloadClient != null ? payloadClient : new PosFlexibleMedicinePayloadClient();
    }

    public static final class FixtureExecutionResult {
        public final String fixtureId;
        public final boolean passed;
        public final String expectedSummary;
        public final String observedSummary;
        public final PosFlexibleMedicinePayloadClient.PayloadValidationReport report;

        public FixtureExecutionResult(
                String fixtureId,
                boolean passed,
                String expectedSummary,
                String observedSummary,
                PosFlexibleMedicinePayloadClient.PayloadValidationReport report) {
            this.fixtureId = fixtureId;
            this.passed = passed;
            this.expectedSummary = expectedSummary;
            this.observedSummary = observedSummary;
            this.report = report;
        }
    }

    public boolean canSeedCatalog(String userRole) {
        return payloadClient.canMutate(userRole);
    }

    public FixtureExecutionResult runPositiveFixture(
            String fixtureId,
            String schemaVersion,
            String dosageFormFamily,
            Map<String, Object> attributes) {

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                payloadClient.validatePayload(schemaVersion, dosageFormFamily, attributes);

        boolean passed = report.isValid && report.errorCount == 0;
        String expected = "SUCCESS (0 errors)";
        String observed = "isValid=" + report.isValid + ", errors=" + report.errorCount;

        return new FixtureExecutionResult(fixtureId, passed, expected, observed, report);
    }

    public FixtureExecutionResult runNegativeFixture(
            String fixtureId,
            String expectedErrorCode,
            String schemaVersion,
            String dosageFormFamily,
            Map<String, Object> attributes) {

        PosFlexibleMedicinePayloadClient.PayloadValidationReport report =
                payloadClient.validatePayload(schemaVersion, dosageFormFamily, attributes);

        boolean hasExpectedError = report.errors.stream()
                .anyMatch(e -> expectedErrorCode.equalsIgnoreCase(e.getErrorCode()));
        boolean passed = (!report.isValid) && hasExpectedError;

        String expected = "REJECT with " + expectedErrorCode;
        List<String> observedCodes = new ArrayList<>();
        for (PosFlexibleMedicinePayloadClient.FieldError fe : report.errors) {
            observedCodes.add(fe.getErrorCode());
        }
        String observed = "isValid=" + report.isValid + ", observed=" + observedCodes;

        return new FixtureExecutionResult(fixtureId, passed, expected, observed, report);
    }
}

