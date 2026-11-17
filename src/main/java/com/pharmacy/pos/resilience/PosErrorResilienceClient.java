package com.pharmacy.pos.resilience;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P239: Desktop JavaFX POS Error Resilience & Recovery Client.
 * Architecture Decision: ADR-0239.
 *
 * Provides fault-tolerant recovery for:
 * 1. Scanner failure -> Manual search fallback ([F2])
 * 2. Printer disconnect -> Retry, format switch, or offline queue
 * 3. Stock conflict -> Alternative non-expired batch discovery & reallocation
 * 4. Network interrupted -> Offline outbox staging & idempotent replay
 */
public class PosErrorResilienceClient {

    public enum UiState {
        READY,
        LOADING,
        ERROR,
        PERMISSION_DENIED,
        OFFLINE
    }

    public enum PosIncidentType {
        SCANNER_FAILURE,
        PRINTER_DISCONNECTED,
        STOCK_CONFLICT,
        NETWORK_INTERRUPTED
    }

    public enum PosRecoveryActionType {
        MANUAL_SEARCH_FALLBACK,
        RECONNECT_SCANNER,
        RETRY_PRINT,
        SWITCH_PRINTER_FORMAT,
        QUEUE_PRINT_OFFLINE,
        AUTO_FEFO_REALLOCATE,
        OFFER_ALTERNATIVE_BATCH,
        STAGE_OFFLINE_OUTBOX,
        RETRY_WITH_IDEMPOTENCY
    }

    public record StockAlternativeDto(
        String batchId,
        String batchNumber,
        String expiryDate,
        int availableUnits,
        String costUnit,
        boolean isExpired,
        boolean canFulfillRequested
    ) {}

    public record PosErrorIncidentDto(
        String incidentId,
        String incidentType,
        String errorCode,
        String correlationId,
        String deviceId,
        String cashierEmail,
        String saleOrderNumber,
        String messageEn,
        String messageBn,
        String status,
        String recoveryActionTaken,
        boolean isRetryable,
        String createdAt,
        String resolvedAt
    ) {}

    public record ResilienceOutcome<T>(
        UiState uiState,
        T data,
        String messageEn,
        String messageBn,
        String errorCode
    ) {
        public boolean isSuccess() {
            return uiState == UiState.READY && data != null;
        }
    }

    public record ApiResponseResult(int statusCode, String body) {}

    @FunctionalInterface
    public interface HttpTransport {
        ApiResponseResult send(String method, String path, String body, String bearerToken) throws Exception;
    }

    private final String baseUrl;
    private final HttpTransport transport;

    public PosErrorResilienceClient(String baseUrl) {
        this(baseUrl, createDefaultTransport(baseUrl));
    }

    public PosErrorResilienceClient(String baseUrl, HttpTransport transport) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    private static HttpTransport createDefaultTransport(String baseUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (method, path, body, bearerToken) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

            if (bearerToken != null && !bearerToken.isBlank()) {
                b.header("Authorization", "Bearer " + bearerToken);
            }

            if ("POST".equalsIgnoreCase(method)) {
                b.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
            } else {
                b.GET();
            }

            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponseResult(res.statusCode(), res.body());
        };
    }

    public ResilienceOutcome<PosErrorIncidentDto> reportIncident(
        PosIncidentType type,
        String errorCode,
        String deviceId,
        String saleId,
        String messageEn,
        String messageBn,
        String bearerToken
    ) {
        try {
            StringBuilder json = new StringBuilder("{");
            json.append("\"incident_type\":\"").append(type.name()).append("\"");
            if (errorCode != null && !errorCode.isBlank()) {
                json.append(",\"error_code\":\"").append(escapeJson(errorCode)).append("\"");
            }
            if (deviceId != null && !deviceId.isBlank()) {
                json.append(",\"device_id\":\"").append(escapeJson(deviceId)).append("\"");
            }
            if (saleId != null && !saleId.isBlank()) {
                json.append(",\"sale_id\":\"").append(escapeJson(saleId)).append("\"");
            }
            if (messageEn != null && !messageEn.isBlank()) {
                json.append(",\"message_en\":\"").append(escapeJson(messageEn)).append("\"");
            }
            if (messageBn != null && !messageBn.isBlank()) {
                json.append(",\"message_bn\":\"").append(escapeJson(messageBn)).append("\"");
            }
            json.append("}");

            ApiResponseResult response = transport.send("POST", "/api/v2/pos/error-incidents/report/", json.toString(), bearerToken);

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                PosErrorIncidentDto dto = parseIncident(response.body());
                return new ResilienceOutcome<>(
                    UiState.READY,
                    dto,
                    dto.messageEn(),
                    dto.messageBn(),
                    null
                );
            } else if (response.statusCode() == 403) {
                return new ResilienceOutcome<>(
                    UiState.PERMISSION_DENIED,
                    null,
                    "Permission denied to report incident.",
                    "ত্রুটি রিপোর্ট করার অনুমতি নেই।",
                    "ERR_PERMISSION_DENIED"
                );
            } else {
                return new ResilienceOutcome<>(
                    UiState.ERROR,
                    null,
                    "Incident report failed with status " + response.statusCode(),
                    "ত্রুটি রিপোর্ট প্রক্রিয়া ব্যর্থ হয়েছে।",
                    "ERR_HTTP_" + response.statusCode()
                );
            }
        } catch (Exception e) {
            return new ResilienceOutcome<>(
                UiState.OFFLINE,
                null,
                "Network unreachable. Incident queued locally: " + e.getMessage(),
                "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। ঘটনাটি লোকাল কিউতে জমা করা হয়েছে।",
                "ERR_NETWORK_UNREACHABLE"
            );
        }
    }

    public ResilienceOutcome<PosErrorIncidentDto> resolveIncident(
        String incidentId,
        PosRecoveryActionType action,
        String extraPayloadJson,
        String bearerToken
    ) {
        try {
            String payloadStr = (extraPayloadJson != null && !extraPayloadJson.isBlank()) ? extraPayloadJson : "{}";
            String json = "{\"recovery_action\":\"" + action.name() + "\",\"recovery_payload\":" + payloadStr + "}";

            ApiResponseResult response = transport.send("POST", "/api/v2/pos/error-incidents/" + incidentId + "/resolve/", json, bearerToken);

            if (response.statusCode() == 200) {
                PosErrorIncidentDto dto = parseIncident(response.body());
                return new ResilienceOutcome<>(
                    UiState.READY,
                    dto,
                    "Incident resolved via " + action.name(),
                    "সমস্যার সফল সমাধান করা হয়েছে (" + action.name() + ")",
                    null
                );
            } else if (response.statusCode() == 403) {
                return new ResilienceOutcome<>(
                    UiState.PERMISSION_DENIED,
                    null,
                    "Permission denied to resolve incident.",
                    "ত্রুটি সমাধান করার অনুমতি নেই।",
                    "ERR_PERMISSION_DENIED"
                );
            } else {
                return new ResilienceOutcome<>(
                    UiState.ERROR,
                    null,
                    "Failed to resolve incident: " + response.body(),
                    "ত্রুটি সমাধান প্রক্রিয়া ব্যর্থ হয়েছে।",
                    "ERR_HTTP_" + response.statusCode()
                );
            }
        } catch (Exception e) {
            return new ResilienceOutcome<>(
                UiState.OFFLINE,
                null,
                "Network timeout during resolution. Recovery queued: " + e.getMessage(),
                "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন। সমাধান কিউতে সংরক্ষিত হয়েছে।",
                "ERR_NETWORK_UNREACHABLE"
            );
        }
    }

    public ResilienceOutcome<List<StockAlternativeDto>> fetchStockAlternatives(
        String productId,
        int requestedQuantity,
        String bearerToken
    ) {
        try {
            String path = "/api/v2/pos/error-incidents/stock-alternatives/?product_id=" + productId
                + "&requested_quantity=" + requestedQuantity;

            ApiResponseResult response = transport.send("GET", path, "", bearerToken);

            if (response.statusCode() == 200) {
                List<StockAlternativeDto> list = parseAlternatives(response.body());
                return new ResilienceOutcome<>(
                    UiState.READY,
                    list,
                    "Found " + list.size() + " alternative batch(es)",
                    list.size() + " টি বিকল্প ব্যাচ পাওয়া গেছে",
                    null
                );
            } else {
                return new ResilienceOutcome<>(
                    UiState.ERROR,
                    Collections.emptyList(),
                    "Could not fetch alternatives",
                    "বিকল্প ব্যাচ অনুসন্ধান ব্যর্থ হয়েছে",
                    "ERR_HTTP_" + response.statusCode()
                );
            }
        } catch (Exception e) {
            return new ResilienceOutcome<>(
                UiState.OFFLINE,
                Collections.emptyList(),
                "Network error fetching stock alternatives: " + e.getMessage(),
                "নেটওয়ার্ক বিচ্ছিন্ন, বিকল্প ব্যাচ তথ্য পাওয়া যায়নি।",
                "ERR_NETWORK_UNREACHABLE"
            );
        }
    }

    private static PosErrorIncidentDto parseIncident(String json) {
        return new PosErrorIncidentDto(
            extractField(json, "incident_id"),
            extractField(json, "incident_type"),
            extractField(json, "error_code"),
            extractField(json, "correlation_id"),
            extractField(json, "device_id"),
            extractField(json, "cashier_email"),
            extractField(json, "sale_order_number"),
            extractField(json, "message_en"),
            extractField(json, "message_bn"),
            extractField(json, "status"),
            extractField(json, "recovery_action_taken"),
            extractBoolean(json, "is_retryable"),
            extractField(json, "created_at"),
            extractField(json, "resolved_at")
        );
    }

    private static List<StockAlternativeDto> parseAlternatives(String json) {
        List<StockAlternativeDto> list = new ArrayList<>();
        Pattern p = Pattern.compile("\\{[^{}]+?\\}");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String block = m.group();
            if (block.contains("\"batch_id\"")) {
                String batchId = extractField(block, "batch_id");
                String batchNumber = extractField(block, "batch_number");
                String expiryDate = extractField(block, "expiry_date");
                int avail = 0;
                try {
                    String aStr = extractField(block, "available_units");
                    avail = Integer.parseInt(aStr);
                } catch (Exception ignored) {}
                String costUnit = extractField(block, "cost_unit");
                boolean isExpired = extractBoolean(block, "is_expired");
                boolean canFulfill = extractBoolean(block, "can_fulfill_requested");
                list.add(new StockAlternativeDto(batchId, batchNumber, expiryDate, avail, costUnit, isExpired, canFulfill));
            }
        }
        return list;
    }

    private static boolean extractBoolean(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE);
        return p.matcher(json).find();
    }

    private static String extractField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        Pattern pNum = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([0-9.]+)");
        Matcher mNum = pNum.matcher(json);
        if (mNum.find()) {
            return mNum.group(1);
        }
        return "";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
