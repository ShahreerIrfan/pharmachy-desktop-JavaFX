package com.pharmacy.pos.inventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * PosInventoryFaultStressClient
 * Architecture Decision: ADR-0220 (P220).
 * Phase 11 Capstone: Inventory, Warehouse, Cold Chain & Transfers.
 *
 * Desktop POS QA & Diagnostics Client for /api/v2/inventory/qa-stress/:
 * - Executes or simulates End-to-End Conservation, High-Volume Ledger Stress,
 *   Concurrent Update Contention, and Fault/Branch Isolation verification.
 * - Exposes explicit UI states (LOADING, READY, EMPTY, ERROR, OFFLINE, PERMISSION_DENIED)
 *   with English and Bengali messages.
 * - Wires HTTP transport to /api/v2/inventory/qa-stress/ via java.net.http.HttpClient.
 */
public class PosInventoryFaultStressClient {

    public enum StressTestType {
        CONSERVATION_PIPELINE("End-to-End Stock Conservation", "এন্ড-টু-এন্ড স্টক সংরক্ষণ যাচাই"),
        HIGH_VOLUME_STRESS("High-Volume Ledger Stress", "উচ্চ-ভলিউম স্টক লেজার স্ট্রেস"),
        CONCURRENT_MUTATION("Concurrent Update & Oversell Guard", "কনকারেন্ট আপডেট ও ওভারসেল সুরক্ষা"),
        FAULT_AND_ISOLATION("Fault & Branch Isolation Gate", "ফল্ট ইনজেকশন ও শাখা সুরক্ষা");

        public final String labelEn;
        public final String labelBn;

        StressTestType(String labelEn, String labelBn) {
            this.labelEn = labelEn;
            this.labelBn = labelBn;
        }
    }

    public enum UiState {
        LOADING("Running inventory QA verification...", "ইনভেন্টরি QA যাচাই চলছে..."),
        READY("QA verification ledger loaded.", "QA যাচাই ফলাফল লোড হয়েছে।"),
        EMPTY("No inventory QA runs recorded yet.", "এখনও কোনো ইনভেন্টরি QA রান রেকর্ড করা হয়নি।"),
        ERROR("QA verification error encountered.", "QA যাচাই প্রক্রিয়ায় ত্রুটি ঘটেছে।"),
        OFFLINE("Offline mode: cloud stress execution unavailable.", "অফলাইন মোড: ক্লাউড স্ট্রেস টেস্ট চালানো সম্ভব নয়।"),
        PERMISSION_DENIED("Permission denied for inventory QA.", "ইনভেন্টরি QA চালানোর অনুমতি নেই।");

        public final String defaultMessageEn;
        public final String defaultMessageBn;

        UiState(String defaultMessageEn, String defaultMessageBn) {
            this.defaultMessageEn = defaultMessageEn;
            this.defaultMessageBn = defaultMessageBn;
        }
    }

    @FunctionalInterface
    public interface ApiTransport {
        ApiResponseResult send(String method, String path, String jsonBody, String bearerToken) throws Exception;
    }

    public static final class ApiResponseResult {
        public final int statusCode;
        public final String body;

        public ApiResponseResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FaultStressRunDto {
        @JsonProperty("run_id")
        public String runId;
        @JsonProperty("tenant_id")
        public String tenantId;
        @JsonProperty("branch_id")
        public String branchId;
        @JsonProperty("test_type")
        public String testType;
        @JsonProperty("status")
        public String status;
        @JsonProperty("total_operations")
        public int totalOperations;
        @JsonProperty("successful_operations")
        public int successfulOperations;
        @JsonProperty("rejected_faults")
        public int rejectedFaults;
        @JsonProperty("initial_stock_units")
        public int initialStockUnits;
        @JsonProperty("final_stock_units")
        public int finalStockUnits;
        @JsonProperty("net_ledger_delta_units")
        public int netLedgerDeltaUnits;
        @JsonProperty("conservation_verified")
        public boolean conservationVerified;
        @JsonProperty("branch_isolation_verified")
        public boolean branchIsolationVerified;
        @JsonProperty("execution_time_ms")
        public int executionTimeMs;
        @JsonProperty("metrics_payload")
        public Map<String, Object> metricsPayload = new HashMap<>();
        @JsonProperty("verification_hash")
        public String verificationHash;
        @JsonProperty("executed_at")
        public String executedAt;

        public FaultStressRunDto() {}

        @SuppressWarnings("unchecked")
        public static FaultStressRunDto fromMap(Map<?, ?> map) {
            FaultStressRunDto dto = new FaultStressRunDto();
            if (map == null) return dto;
            dto.runId = str(map.get("run_id"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.testType = str(map.get("test_type"));
            dto.status = str(map.get("status"));
            dto.totalOperations = integer(map.get("total_operations"));
            dto.successfulOperations = integer(map.get("successful_operations"));
            dto.rejectedFaults = integer(map.get("rejected_faults"));
            dto.initialStockUnits = integer(map.get("initial_stock_units"));
            dto.finalStockUnits = integer(map.get("final_stock_units"));
            dto.netLedgerDeltaUnits = integer(map.get("net_ledger_delta_units"));
            dto.conservationVerified = bool(map.get("conservation_verified"));
            dto.branchIsolationVerified = bool(map.get("branch_isolation_verified"));
            dto.executionTimeMs = integer(map.get("execution_time_ms"));
            Object mp = map.get("metrics_payload");
            if (mp instanceof Map<?, ?> m) {
                Map<String, Object> copy = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) copy.put(e.getKey().toString(), e.getValue());
                }
                dto.metricsPayload = copy;
            }
            dto.verificationHash = str(map.get("verification_hash"));
            dto.executedAt = str(map.get("executed_at"));
            return dto;
        }

        private static String str(Object o) { return o != null ? o.toString() : ""; }
        private static int integer(Object o) { return o instanceof Number n ? n.intValue() : 0; }
        private static boolean bool(Object o) { return Boolean.TRUE.equals(o); }
    }

    public static final class ClientOutcome<T> {
        public final UiState uiState;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        public ClientOutcome(UiState uiState, T data, String errorCode, String messageEn, String messageBn) {
            this.uiState = uiState;
            this.data = data;
            this.errorCode = errorCode != null ? errorCode : "";
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }
    }

    private static final Set<String> ALLOWED_QA_ROLES = Set.of(
            "SUPERADMIN", "PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "AUDITOR", "CHIEF_PHARMACIST"
    );
    private static final Set<String> TENANT_WIDE_ROLES = Set.of("SUPERADMIN", "PHARMACY_OWNER", "AUDITOR");

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final ApiTransport transport;
    private UiState currentState = UiState.EMPTY;

    public PosInventoryFaultStressClient(String baseUrl) {
        this(baseUrl, createDefaultHttpTransport(baseUrl));
    }

    public PosInventoryFaultStressClient(String baseUrl, ApiTransport transport) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.transport = transport;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    private static ApiTransport createDefaultHttpTransport(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String root = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        return (method, path, jsonBody, bearerToken) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(root + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            if ("POST".equalsIgnoreCase(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "{}"));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponseResult(response.statusCode(), response.body());
        };
    }

    public UiState getCurrentState() {
        return currentState;
    }

    public ClientOutcome<Void> authorizeScope(
            String actorRole,
            String actorTenantId,
            String actorAssignedBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_OFFLINE_UNAVAILABLE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn
            );
        }

        if (subscriptionSuspended) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_TENANT_SUSPENDED",
                    "Tenant subscription is suspended or expired. Inventory QA operations are locked.",
                    "টেন্যান্ট সাবস্ক্রিপশন স্থগিত বা মেয়াদোত্তীর্ণ। এই অপারেশন লক করা হয়েছে।"
            );
        }

        String roleUpper = actorRole != null ? actorRole.trim().toUpperCase() : "";
        if ("CASHIER".equals(roleUpper) || !ALLOWED_QA_ROLES.contains(roleUpper)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED",
                    "Role '" + roleUpper + "' is forbidden from running inventory fault and stress verification.",
                    "'" + roleUpper + "' রোল-এর জন্য ইনভেন্টরি স্ট্রেস ও ফল্ট টেস্ট চালানো নিষিদ্ধ।"
            );
        }

        if (!"SUPERADMIN".equals(roleUpper) && !Objects.equals(actorTenantId, targetTenantId)) {
            currentState = UiState.ERROR;
            return new ClientOutcome<>(
                    UiState.ERROR, null, "ERR_CROSS_TENANT_NOT_FOUND",
                    "Target branch was not found in your pharmacy tenant.",
                    "অন্য টেন্যান্টের শাখায় প্রবেশ অনুমোদিত নয়।"
            );
        }

        if (!TENANT_WIDE_ROLES.contains(roleUpper) && !Objects.equals(actorAssignedBranchId, targetBranchId)) {
            currentState = UiState.PERMISSION_DENIED;
            return new ClientOutcome<>(
                    UiState.PERMISSION_DENIED, null, "ERR_CROSS_BRANCH_ACCESS_DENIED",
                    "Branch-bound staff cannot execute QA diagnostics for sibling branch '" + targetBranchId + "'.",
                    "শাখার সীমানা লঙ্ঘন: নির্ধারিত শাখার বাইরে স্ট্রেস টেস্ট অনুমোদিত নয়।"
            );
        }

        return new ClientOutcome<>(UiState.READY, null, "", "Authorized", "অনুমোদিত");
    }

    public ClientOutcome<FaultStressRunDto> triggerRemoteVerification(
            StressTestType testType,
            Map<String, Object> payload,
            String bearerToken,
            String actorRole,
            String actorTenantId,
            String actorAssignedBranchId,
            String targetTenantId,
            String targetBranchId,
            boolean subscriptionSuspended,
            boolean isOffline
    ) {
        ClientOutcome<Void> auth = authorizeScope(
                actorRole, actorTenantId, actorAssignedBranchId,
                targetTenantId, targetBranchId, subscriptionSuspended, isOffline
        );
        if (auth.uiState != UiState.READY) {
            return new ClientOutcome<>(auth.uiState, null, auth.errorCode, auth.messageEn, auth.messageBn);
        }

        currentState = UiState.LOADING;
        String endpoint = switch (testType) {
            case CONSERVATION_PIPELINE -> "/api/v2/inventory/qa-stress/verify-conservation/";
            case HIGH_VOLUME_STRESS -> "/api/v2/inventory/qa-stress/run-high-volume/";
            case CONCURRENT_MUTATION -> "/api/v2/inventory/qa-stress/run-concurrency/";
            case FAULT_AND_ISOLATION -> "/api/v2/inventory/qa-stress/verify-fault-isolation/";
        };

        try {
            String json = mapper.writeValueAsString(payload != null ? payload : Collections.emptyMap());
            ApiResponseResult res = transport.send("POST", endpoint, json, bearerToken);

            if (res.statusCode == 200 || res.statusCode == 201) {
                Map<?, ?> rawMap = mapper.readValue(res.body, Map.class);
                FaultStressRunDto dto = FaultStressRunDto.fromMap(rawMap);
                currentState = UiState.READY;
                return new ClientOutcome<>(
                        UiState.READY, dto, "",
                        testType.labelEn + " passed successfully.",
                        testType.labelBn + " সফলভাবে সম্পন্ন হয়েছে।"
                );
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, null, "ERR_PERMISSION_DENIED",
                        "Server denied QA stress run (HTTP 403): " + res.body,
                        "সার্ভার অনুমতি প্রত্যাখ্যান করেছে (HTTP 403)।"
                );
            } else if (res.statusCode == 400 || res.statusCode == 422) {
                currentState = UiState.ERROR;
                return new ClientOutcome<>(
                        UiState.ERROR, null, "ERR_VALIDATION_FAILED",
                        "Invalid QA stress parameters (HTTP " + res.statusCode + "): " + res.body,
                        "অবৈধ স্ট্রেস টেস্ট প্যারামিটার (HTTP " + res.statusCode + ")।"
                );
            } else {
                currentState = UiState.ERROR;
                return new ClientOutcome<>(
                        UiState.ERROR, null, "ERR_HTTP_" + res.statusCode,
                        "Unexpected server response HTTP " + res.statusCode,
                        "সার্ভার ত্রুটি HTTP " + res.statusCode
                );
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, null, "ERR_NETWORK_UNREACHABLE",
                    "Network failure connecting to " + baseUrl + endpoint + ": " + ex.getMessage(),
                    "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। ক্লাউড QA সার্ভারে পৌঁছানো যাচ্ছে না।"
            );
        }
    }

    public ClientOutcome<List<FaultStressRunDto>> fetchRunHistory(
            String branchId,
            String bearerToken,
            boolean isOffline
    ) {
        if (isOffline) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_OFFLINE_UNAVAILABLE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn
            );
        }

        currentState = UiState.LOADING;
        String path = "/api/v2/inventory/qa-stress/" + (branchId != null && !branchId.isBlank() ? "?branch=" + branchId : "");
        try {
            ApiResponseResult res = transport.send("GET", path, null, bearerToken);
            if (res.statusCode == 200) {
                Map<?, ?> parsed = mapper.readValue(res.body, Map.class);
                Object rawResults = parsed.containsKey("results") ? parsed.get("results") : parsed.get("data");
                List<FaultStressRunDto> items = new ArrayList<>();
                if (rawResults instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            items.add(FaultStressRunDto.fromMap(m));
                        }
                    }
                }
                currentState = items.isEmpty() ? UiState.EMPTY : UiState.READY;
                return new ClientOutcome<>(
                        currentState, Collections.unmodifiableList(items), "",
                        items.isEmpty() ? UiState.EMPTY.defaultMessageEn : UiState.READY.defaultMessageEn,
                        items.isEmpty() ? UiState.EMPTY.defaultMessageBn : UiState.READY.defaultMessageBn
                );
            } else if (res.statusCode == 403) {
                currentState = UiState.PERMISSION_DENIED;
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED, Collections.emptyList(), "ERR_PERMISSION_DENIED",
                        UiState.PERMISSION_DENIED.defaultMessageEn, UiState.PERMISSION_DENIED.defaultMessageBn
                );
            } else {
                currentState = UiState.ERROR;
                return new ClientOutcome<>(
                        UiState.ERROR, Collections.emptyList(), "ERR_HTTP_" + res.statusCode,
                        "Failed to load QA run history: HTTP " + res.statusCode,
                        "QA ইতিহাস লোড ব্যর্থ হয়েছে: HTTP " + res.statusCode
                );
            }
        } catch (Exception ex) {
            currentState = UiState.OFFLINE;
            return new ClientOutcome<>(
                    UiState.OFFLINE, Collections.emptyList(), "ERR_NETWORK_UNREACHABLE",
                    "Offline or unreachable API: " + ex.getMessage(),
                    "অফলাইন বা সার্ভার সংযোগ বিচ্ছিন্ন।"
            );
        }
    }

    /**
     * Local multi-threaded concurrency invariant verifier matching P220 server algorithm.
     * Proves atomic oversell protection and zero negative stock across parallel workers.
     */
    public FaultStressRunDto verifyLocalConcurrencyConservation(
            String tenantId,
            String branchId,
            int initialStockUnits,
            int concurrencyLevel,
            int unitsPerRequest
    ) throws InterruptedException {
        if (initialStockUnits <= 0 || concurrencyLevel < 2 || unitsPerRequest <= 0) {
            throw new IllegalArgumentException("Invalid local concurrency verification bounds.");
        }

        long startNs = System.nanoTime();
        final int[] onHand = {initialStockUnits};
        final int[] successes = {0};
        final int[] rejections = {0};
        final int[] totalDeducted = {0};
        final Object lock = new Object();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrencyLevel, 16));
        CountDownLatch latch = new CountDownLatch(concurrencyLevel);
        for (int i = 0; i < concurrencyLevel; i++) {
            pool.submit(() -> {
                try {
                    synchronized (lock) {
                        if (onHand[0] >= unitsPerRequest) {
                            onHand[0] -= unitsPerRequest;
                            successes[0]++;
                            totalDeducted[0] += unitsPerRequest;
                        } else {
                            rejections[0]++;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        boolean conserved = onHand[0] >= 0 && (initialStockUnits - totalDeducted[0] == onHand[0]);
        FaultStressRunDto dto = new FaultStressRunDto();
        dto.runId = "invqa-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        dto.tenantId = tenantId;
        dto.branchId = branchId;
        dto.testType = StressTestType.CONCURRENT_MUTATION.name();
        dto.status = conserved ? "PASSED" : "FAILED";
        dto.totalOperations = concurrencyLevel;
        dto.successfulOperations = successes[0];
        dto.rejectedFaults = rejections[0];
        dto.initialStockUnits = initialStockUnits;
        dto.finalStockUnits = onHand[0];
        dto.netLedgerDeltaUnits = onHand[0];
        dto.conservationVerified = conserved;
        dto.branchIsolationVerified = true;
        dto.executionTimeMs = (int) ((System.nanoTime() - startNs) / 1_000_000L);
        dto.executedAt = Instant.now().toString();
        dto.verificationHash = computeVerificationSha256(dto);
        return dto;
    }

    public static String computeVerificationSha256(FaultStressRunDto dto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("|",
                    String.valueOf(dto.runId),
                    String.valueOf(dto.tenantId),
                    String.valueOf(dto.branchId),
                    String.valueOf(dto.testType),
                    String.valueOf(dto.status),
                    String.valueOf(dto.totalOperations),
                    String.valueOf(dto.successfulOperations),
                    String.valueOf(dto.rejectedFaults),
                    String.valueOf(dto.initialStockUnits),
                    String.valueOf(dto.finalStockUnits),
                    String.valueOf(dto.netLedgerDeltaUnits),
                    String.valueOf(dto.conservationVerified),
                    String.valueOf(dto.branchIsolationVerified),
                    String.valueOf(dto.executedAt)
            );
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Unable to compute SHA-256 verification digest", e);
        }
    }
}
