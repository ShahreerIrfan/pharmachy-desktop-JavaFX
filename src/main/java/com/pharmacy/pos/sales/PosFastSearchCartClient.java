package com.pharmacy.pos.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmacy.pos.models.CartItem;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiResponseResult;
import com.pharmacy.pos.sales.PosSaleDomainClient.ApiTransport;
import com.pharmacy.pos.sales.PosSaleDomainClient.ClientOutcome;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleLineDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.PosSaleOrderDto;
import com.pharmacy.pos.sales.PosSaleDomainClient.UiState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosFastSearchCartClient
 * Architecture Decision: ADR-0222 (P222).
 * Phase 12: POS, Sales Orders, Checkout & Returns.
 *
 * Wired to /api/v2/pos/search/ and /api/v2/pos/sales/{quick-cart,scan-add,cart-line}:
 * - High-speed prioritized lookup across barcodes, packaging tier GTINs, variant SKUs, and EN/BN brand/generic names.
 * - Validated SKU Inventory Snapshot cache per branch for sub-second offline counter lookup
 *   (strictly labeled OFFLINE / estimate-only while disconnected; never asserts live cloud stock accuracy).
 * - JPMS-safe manual Map parsing with BigDecimal HALF_UP arithmetic and EN/BN UiState feedback.
 */
public class PosFastSearchCartClient {

    public static final class PackagingOptionDto {
        public String packagingUnit = "TABLET";
        public String labelEn = "Tablet / Unit";
        public String labelBn = "ট্যাবলেট / পিস";
        public int conversionFactor = 1;
        public BigDecimal unitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public String conversionSource = "";
        public String containerPolicyStatus = "STANDARD";
        public boolean isAllowed = true;
        public boolean requiresPharmacistApproval = false;
        public String blockErrorCode = "";
        public String blockReasonEn = "";
        public String blockReasonBn = "";

        public static PackagingOptionDto fromMap(Map<?, ?> map) {
            PackagingOptionDto dto = new PackagingOptionDto();
            if (map == null) return dto;
            dto.packagingUnit = strOr(map.get("packaging_unit"), "TABLET");
            dto.labelEn = strOr(map.get("label_en"), dto.packagingUnit);
            dto.labelBn = strOr(map.get("label_bn"), dto.labelEn);
            dto.conversionFactor = Math.max(1, integer(map.get("conversion_factor"), 1));
            dto.unitPriceBdt = dec2(map.get("unit_price_bdt"));
            dto.conversionSource = str(map.get("conversion_source"));
            dto.containerPolicyStatus = strOr(map.get("container_policy_status"), "STANDARD");
            dto.isAllowed = map.get("is_allowed") == null || Boolean.TRUE.equals(map.get("is_allowed"));
            dto.requiresPharmacistApproval = Boolean.TRUE.equals(map.get("requires_pharmacist_approval"));
            dto.blockErrorCode = str(map.get("block_error_code"));
            dto.blockReasonEn = str(map.get("block_reason_en"));
            dto.blockReasonBn = str(map.get("block_reason_bn"));
            return dto;
        }

        @Override
        public String toString() {
            String lockTag = isAllowed ? "" : (requiresPharmacistApproval ? " [Pharmacist]" : " [Locked]");
            return labelEn + " - BDT " + unitPriceBdt.toPlainString() + lockTag;
        }
    }

    public static final class UnitOptionsResponseDto {
        public String tenantId = "";
        public String branchId = "";
        public String saleId = "";
        public String lineId = "";
        public String currentPackagingUnit = "";
        public int currentConversionFactor = 1;
        public int currentQuantityUnits = 1;
        public int currentBaseUnitsTotal = 1;
        public String productId = "";
        public String variantId = "";
        public String brandName = "";
        public String dosageForm = "";
        public String normalizedDosageForm = "";
        public String primaryBaseUnit = "TABLET";
        public int availableBaseUnits = 0;
        public String conversionSource = "";
        public boolean nonDivisibleSealedOnly = false;
        public final List<String> blockedUnits = new ArrayList<>();
        public final List<PackagingOptionDto> packagingOptions = new ArrayList<>();

        public static UnitOptionsResponseDto fromMap(Map<?, ?> map) {
            UnitOptionsResponseDto dto = new UnitOptionsResponseDto();
            if (map == null) return dto;
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.saleId = str(map.get("sale_id"));
            dto.lineId = str(map.get("line_id"));
            dto.currentPackagingUnit = str(map.get("current_packaging_unit"));
            dto.currentConversionFactor = integer(map.get("current_conversion_factor"), 1);
            dto.currentQuantityUnits = integer(map.get("current_quantity_units"), 1);
            dto.currentBaseUnitsTotal = integer(map.get("current_base_units_total"), 1);
            dto.productId = str(map.get("product_id"));
            dto.variantId = str(map.get("variant_id"));
            dto.brandName = str(map.get("brand_name"));
            dto.dosageForm = str(map.get("dosage_form"));
            dto.normalizedDosageForm = str(map.get("normalized_dosage_form"));
            dto.primaryBaseUnit = strOr(map.get("primary_base_unit"), "TABLET");
            dto.availableBaseUnits = integer(map.get("available_base_units"), 0);
            dto.conversionSource = str(map.get("conversion_source"));
            dto.nonDivisibleSealedOnly = Boolean.TRUE.equals(map.get("non_divisible_sealed_only"));
            if (map.get("blocked_units") instanceof List<?> blk) {
                for (Object b : blk) {
                    if (b != null) dto.blockedUnits.add(b.toString().trim());
                }
            }
            if (map.get("packaging_options") instanceof List<?> opts) {
                for (Object o : opts) {
                    if (o instanceof Map<?, ?> om) {
                        dto.packagingOptions.add(PackagingOptionDto.fromMap(om));
                    }
                }
            }
            return dto;
        }
    }

    public static final class FastSearchCandidateDto {
        public String productId = "";
        public String variantId = "";
        public String skuCode = "";
        public String displayName = "";
        public String brandName = "";
        public String brandNameBn = "";
        public String genericName = "";
        public String genericNameBn = "";
        public String strength = "";
        public String dosageForm = "";
        public String barcode = "";
        public String shelfLocation = "";
        public String resolutionSource = "";
        public String resolvedPackagingUnit = "TABLET";
        public int resolvedConversionFactor = 1;
        public BigDecimal unitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal baseUnitPriceBdt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal unitCostBdt = null; // Null/redacted for Cashiers
        public int sellableBaseUnits = 0;
        public int reservedBaseUnits = 0;
        public int availableBaseUnits = 0;
        public int quarantinedBaseUnits = 0;
        public int expiredBaseUnits = 0;
        public int recalledBaseUnits = 0;
        public String earliestBatchId = "";
        public String earliestBatchNumber = "";
        public String earliestExpiryDate = "";
        public boolean nearExpiryWarning = false;
        public boolean requiresRxVerification = false;
        public String regulatoryClassification = "OTC";
        public boolean nonDivisibleSealedOnly = false;
        public boolean isSellable = false;
        public String blockReason = "";
        public String snapshotReadModelId = "";
        public String snapshotChecksum = "";
        public String snapshotAsOf = "";
        public int rankScore = 0;
        public String conversionSource = "";
        public final List<String> blockedUnits = new ArrayList<>();
        public final List<PackagingOptionDto> packagingOptions = new ArrayList<>();

        public static FastSearchCandidateDto fromMap(Map<?, ?> map) {
            FastSearchCandidateDto dto = new FastSearchCandidateDto();
            if (map == null) return dto;
            dto.productId = str(map.get("product_id"));
            dto.variantId = str(map.get("variant_id"));
            dto.skuCode = str(map.get("sku_code"));
            dto.brandName = str(map.get("brand_name"));
            dto.brandNameBn = str(map.get("brand_name_bn"));
            dto.genericName = str(map.get("generic_name"));
            dto.genericNameBn = str(map.get("generic_name_bn"));
            dto.strength = str(map.get("strength"));
            dto.dosageForm = str(map.get("dosage_form"));
            dto.displayName = strOr(map.get("display_name"), (dto.brandName + " " + dto.strength).trim());
            dto.barcode = str(map.get("barcode"));
            dto.shelfLocation = str(map.get("shelf_location"));
            dto.resolutionSource = str(map.get("resolution_source"));
            dto.resolvedPackagingUnit = strOr(map.get("resolved_packaging_unit"), "TABLET");
            dto.resolvedConversionFactor = Math.max(1, integer(map.get("resolved_conversion_factor"), 1));
            dto.unitPriceBdt = dec2(map.get("unit_price_bdt"));
            dto.baseUnitPriceBdt = dec2(map.get("base_unit_price_bdt"));
            if (dto.baseUnitPriceBdt.compareTo(BigDecimal.ZERO) == 0 && dto.unitPriceBdt.compareTo(BigDecimal.ZERO) > 0) {
                dto.baseUnitPriceBdt = dto.unitPriceBdt;
            }
            dto.unitCostBdt = map.get("unit_cost_bdt") != null ? dec4(map.get("unit_cost_bdt")) : null;
            dto.sellableBaseUnits = integer(map.get("sellable_base_units"), 0);
            dto.reservedBaseUnits = integer(map.get("reserved_base_units"), 0);
            dto.availableBaseUnits = integer(map.get("available_base_units"), 0);
            dto.quarantinedBaseUnits = integer(map.get("quarantined_base_units"), 0);
            dto.expiredBaseUnits = integer(map.get("expired_base_units"), 0);
            dto.recalledBaseUnits = integer(map.get("recalled_base_units"), 0);
            dto.earliestBatchId = str(map.get("earliest_batch_id"));
            dto.earliestBatchNumber = str(map.get("earliest_batch_number"));
            dto.earliestExpiryDate = str(map.get("earliest_expiry_date"));
            dto.nearExpiryWarning = Boolean.TRUE.equals(map.get("near_expiry_warning"));
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.regulatoryClassification = strOr(
                    map.get("regulatory_classification"),
                    dto.requiresRxVerification ? "PRESCRIPTION_ONLY" : "OTC"
            );
            dto.nonDivisibleSealedOnly = Boolean.TRUE.equals(map.get("non_divisible_sealed_only"));
            dto.isSellable = Boolean.TRUE.equals(map.get("is_sellable"));
            dto.blockReason = str(map.get("block_reason"));
            dto.snapshotReadModelId = str(map.get("snapshot_read_model_id"));
            dto.snapshotChecksum = str(map.get("snapshot_checksum"));
            dto.snapshotAsOf = str(map.get("snapshot_as_of"));
            dto.rankScore = integer(map.get("rank_score"), 0);
            dto.conversionSource = str(map.get("conversion_source"));

            if (map.get("blocked_units") instanceof List<?> blk) {
                for (Object b : blk) {
                    if (b != null) dto.blockedUnits.add(b.toString().trim());
                }
            }
            Object optsObj = map.get("packaging_options");
            if (optsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> om) {
                        dto.packagingOptions.add(PackagingOptionDto.fromMap(om));
                    }
                }
            }
            return dto;
        }

        public static FastSearchCandidateDto fromSnapshotItemMap(Map<?, ?> map) {
            FastSearchCandidateDto dto = new FastSearchCandidateDto();
            if (map == null) return dto;
            dto.snapshotReadModelId = str(map.get("read_model_id"));
            dto.productId = str(map.get("product_id"));
            dto.variantId = str(map.get("variant_id"));
            dto.skuCode = str(map.get("sku_code"));
            dto.brandName = str(map.get("brand_name"));
            dto.brandNameBn = str(map.get("brand_name_bn"));
            dto.genericName = str(map.get("generic_name"));
            dto.genericNameBn = str(map.get("generic_name_bn"));
            dto.strength = str(map.get("strength"));
            dto.dosageForm = str(map.get("dosage_form"));
            dto.displayName = (dto.brandName + " " + dto.strength + " " + dto.dosageForm).trim();
            dto.barcode = str(map.get("barcode_tokens"));
            dto.unitPriceBdt = dec2(map.get("unit_price_bdt"));
            dto.baseUnitPriceBdt = dto.unitPriceBdt;
            dto.sellableBaseUnits = integer(map.get("sellable_base_units"), 0);
            dto.reservedBaseUnits = integer(map.get("reserved_base_units"), 0);
            dto.availableBaseUnits = integer(map.get("available_base_units"), 0);
            dto.quarantinedBaseUnits = integer(map.get("quarantined_base_units"), 0);
            dto.expiredBaseUnits = integer(map.get("expired_base_units"), 0);
            dto.recalledBaseUnits = integer(map.get("recalled_base_units"), 0);
            dto.earliestExpiryDate = str(map.get("nearest_expiry_date"));
            dto.shelfLocation = str(map.get("shelf_location"));
            dto.requiresRxVerification = Boolean.TRUE.equals(map.get("requires_rx_verification"));
            dto.regulatoryClassification = strOr(
                    map.get("regulatory_classification"),
                    dto.requiresRxVerification ? "PRESCRIPTION_ONLY" : "OTC"
            );
            dto.nonDivisibleSealedOnly = Boolean.TRUE.equals(map.get("non_divisible_sealed_only"));
            dto.snapshotChecksum = str(map.get("sha256_checksum"));
            dto.snapshotAsOf = str(map.get("snapshot_as_of"));
            dto.isSellable = dto.availableBaseUnits > 0;
            dto.resolutionSource = "OFFLINE_SNAPSHOT";
            if (!dto.isSellable) {
                dto.blockReason = dto.expiredBaseUnits > 0 ? "EXPIRED_STOCK_BLOCKED" : "OUT_OF_STOCK";
            }

            int stripF = Math.max(1, integer(map.get("strip_conversion_factor"), 10));
            int boxF = Math.max(stripF, integer(map.get("box_conversion_factor"), 100));
            dto.conversionSource = "OFFLINE_SNAPSHOT";
            PackagingOptionDto t = new PackagingOptionDto();
            t.packagingUnit = "TABLET";
            t.labelEn = "Tablet / Unit";
            t.labelBn = "ট্যাবলেট / পিস";
            t.conversionFactor = 1;
            t.unitPriceBdt = dto.baseUnitPriceBdt;
            t.conversionSource = "OFFLINE_SNAPSHOT";
            if (dto.nonDivisibleSealedOnly) {
                t.isAllowed = false;
                t.containerPolicyStatus = "RESTRICTED";
                t.blockErrorCode = "ERR_NON_DIVISIBLE_PACK";
                t.blockReasonEn = "Non-divisible sealed pack policy blocks loose tablet sales.";
                t.blockReasonBn = "সিলড প্যাক নীতি অনুযায়ী খোলা ট্যাবলেট বিক্রি নিষিদ্ধ।";
                dto.blockedUnits.add("TABLET");
            }
            dto.packagingOptions.add(t);

            PackagingOptionDto s = new PackagingOptionDto();
            s.packagingUnit = "STRIP";
            s.labelEn = "Strip (" + stripF + "u)";
            s.labelBn = "স্ট্রিপ (" + stripF + "টি)";
            s.conversionFactor = stripF;
            s.unitPriceBdt = dto.baseUnitPriceBdt.multiply(BigDecimal.valueOf(stripF)).setScale(2, RoundingMode.HALF_UP);
            s.conversionSource = "OFFLINE_SNAPSHOT";
            dto.packagingOptions.add(s);

            PackagingOptionDto b = new PackagingOptionDto();
            b.packagingUnit = "BOX";
            b.labelEn = "Box (" + boxF + "u)";
            b.labelBn = "বক্স (" + boxF + "টি)";
            b.conversionFactor = boxF;
            b.unitPriceBdt = dto.baseUnitPriceBdt.multiply(BigDecimal.valueOf(boxF)).setScale(2, RoundingMode.HALF_UP);
            b.conversionSource = "OFFLINE_SNAPSHOT";
            dto.packagingOptions.add(b);

            if (dto.nonDivisibleSealedOnly) {
                dto.resolvedPackagingUnit = s.packagingUnit;
                dto.resolvedConversionFactor = s.conversionFactor;
                dto.unitPriceBdt = s.unitPriceBdt;
            }
            return dto;
        }

        public CartItem toLegacyCartItem(int quantity, String selectedUnit, BigDecimal vatRate) {
            String unit = (selectedUnit != null && !selectedUnit.isBlank()) ? selectedUnit : resolvedPackagingUnit;
            BigDecimal price = unitPriceBdt;
            for (PackagingOptionDto opt : packagingOptions) {
                if (opt.packagingUnit.equalsIgnoreCase(unit)) {
                    price = opt.unitPriceBdt;
                    break;
                }
            }
            LocalDate exp;
            try {
                exp = (earliestExpiryDate != null && !earliestExpiryDate.isBlank())
                        ? LocalDate.parse(earliestExpiryDate)
                        : LocalDate.now().plusYears(1);
            } catch (Exception ignored) {
                exp = LocalDate.now().plusYears(1);
            }
            String batch = !earliestBatchNumber.isBlank() ? earliestBatchNumber : "FEFO-PENDING";
            return new CartItem(
                    productId,
                    !brandName.isBlank() ? brandName : displayName,
                    genericName,
                    unit,
                    quantity,
                    price,
                    batch,
                    exp,
                    vatRate != null ? vatRate : BigDecimal.ZERO
            );
        }
    }

    public static final class FastSearchResponseDto {
        public String query = "";
        public String tenantId = "";
        public String branchId = "";
        public int totalMatches = 0;
        public double latencyMs = 0.0;
        public boolean fromOfflineCache = false;
        public final List<FastSearchCandidateDto> results = new ArrayList<>();

        public static FastSearchResponseDto fromMap(Map<?, ?> map) {
            FastSearchResponseDto dto = new FastSearchResponseDto();
            if (map == null) return dto;
            dto.query = str(map.get("query"));
            dto.tenantId = str(map.get("tenant_id"));
            dto.branchId = str(map.get("branch_id"));
            dto.totalMatches = integer(map.get("total_matches"), 0);
            Object lat = map.get("latency_ms");
            if (lat instanceof Number n) dto.latencyMs = n.doubleValue();
            Object resObj = map.get("results");
            if (resObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> cm) {
                        dto.results.add(FastSearchCandidateDto.fromMap(cm));
                    }
                }
            }
            return dto;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final ApiTransport transport;
    private final Map<String, List<FastSearchCandidateDto>> branchSnapshotCache = new ConcurrentHashMap<>();

    public PosFastSearchCartClient() {
        this("http://127.0.0.1:8000", createDefaultTransport("http://127.0.0.1:8000"));
    }

    public PosFastSearchCartClient(String baseUrl, ApiTransport transport) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "http://127.0.0.1:8000";
        this.transport = Objects.requireNonNull(transport, "transport cannot be null");
    }

    private static ApiTransport createDefaultTransport(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        String cleanBase = baseUrl.replaceAll("/+$", "");
        return (method, path, jsonBody, token) -> {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(cleanBase + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (token != null && !token.isBlank()) {
                req.header("Authorization", "Bearer " + token);
            }
            HttpRequest.BodyPublisher bodyPub = (jsonBody == null || jsonBody.isEmpty())
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody);
            req.method(method, bodyPub);
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponseResult(resp.statusCode(), resp.body());
        };
    }

    public void seedOfflineSnapshot(String branchId, List<FastSearchCandidateDto> items) {
        if (branchId != null && items != null) {
            branchSnapshotCache.put(branchId, new ArrayList<>(items));
        }
    }

    public List<FastSearchCandidateDto> getCachedSnapshot(String branchId) {
        return Collections.unmodifiableList(branchSnapshotCache.getOrDefault(branchId, List.of()));
    }

    public FastSearchResponseDto searchOfflineSnapshot(String branchId, String query) {
        long t0 = System.nanoTime();
        FastSearchResponseDto resp = new FastSearchResponseDto();
        resp.query = query != null ? query.trim() : "";
        resp.branchId = branchId != null ? branchId : "";
        resp.fromOfflineCache = true;
        String qLower = resp.query.toLowerCase(Locale.ROOT);
        if (qLower.isEmpty()) return resp;

        List<FastSearchCandidateDto> cached = branchSnapshotCache.getOrDefault(resp.branchId, List.of());
        List<FastSearchCandidateDto> hits = new ArrayList<>();
        for (FastSearchCandidateDto item : cached) {
            int score = 0;
            if (equalsIgnoreCase(item.barcode, qLower) || containsToken(item.barcode, qLower)) {
                score = 120;
            } else if (equalsIgnoreCase(item.skuCode, qLower)) {
                score = 110;
            } else if (item.brandName.toLowerCase(Locale.ROOT).startsWith(qLower)
                    || item.brandNameBn.toLowerCase(Locale.ROOT).startsWith(qLower)) {
                score = 95;
            } else if (item.genericName.toLowerCase(Locale.ROOT).startsWith(qLower)
                    || item.genericNameBn.toLowerCase(Locale.ROOT).startsWith(qLower)) {
                score = 85;
            } else if (item.brandName.toLowerCase(Locale.ROOT).contains(qLower)
                    || item.genericName.toLowerCase(Locale.ROOT).contains(qLower)
                    || item.brandNameBn.toLowerCase(Locale.ROOT).contains(qLower)
                    || item.skuCode.toLowerCase(Locale.ROOT).contains(qLower)) {
                score = 65;
            }
            if (score > 0) {
                FastSearchCandidateDto copy = cloneForOffline(item, score);
                hits.add(copy);
            }
        }
        hits.sort((a, b) -> {
            int cmp = Integer.compare(b.rankScore, a.rankScore);
            if (cmp != 0) return cmp;
            int sCmp = Boolean.compare(b.isSellable, a.isSellable);
            if (sCmp != 0) return sCmp;
            return a.displayName.compareToIgnoreCase(b.displayName);
        });
        resp.results.addAll(hits);
        resp.totalMatches = hits.size();
        resp.latencyMs = Math.round(((System.nanoTime() - t0) / 1_000_000.0) * 100.0) / 100.0;
        return resp;
    }

    public ClientOutcome<FastSearchResponseDto> fastSearch(String branchId, String query, int limit, String token) {
        if (query == null || query.isBlank()) {
            return new ClientOutcome<>(
                    UiState.ERROR,
                    null,
                    "ERR_VALIDATION_FAILED",
                    "Search or barcode input cannot be empty.",
                    "অনুসন্ধান বা বারকোড ইনপুট খালি রাখা যাবে না।"
            );
        }
        try {
            String encQ = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String encB = URLEncoder.encode(branchId != null ? branchId : "", StandardCharsets.UTF_8);
            String path = "/api/v2/pos/search/?branch=" + encB + "&q=" + encQ + "&limit=" + Math.max(1, limit);
            ApiResponseResult res = transport.send("GET", path, "", token);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                Map<?, ?> parsed = parseMap(res.body);
                FastSearchResponseDto dto = FastSearchResponseDto.fromMap(parsed);
                if (dto.results.isEmpty()) {
                    return new ClientOutcome<>(
                            UiState.EMPTY,
                            dto,
                            "",
                            "No matching medicine or SKU found for '" + query + "'.",
                            "'" + query + "' অনুযায়ী কোনো ওষুধ বা SKU পাওয়া যায়নি।"
                    );
                }
                return new ClientOutcome<>(
                        UiState.READY,
                        dto,
                        "",
                        "Found " + dto.results.size() + " matches in " + dto.latencyMs + "ms.",
                        dto.results.size() + "টি ফলাফল পাওয়া গেছে (" + dto.latencyMs + "ms)।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED,
                        null,
                        extractErrorCode(res.body, "ERR_PERMISSION_DENIED"),
                        "Permission denied for branch POS search.",
                        "এই শাখায় পিওএস সার্চের অনুমতি আপনার নেই।"
                );
            }
            return new ClientOutcome<>(
                    UiState.ERROR,
                    null,
                    extractErrorCode(res.body, "ERR_VALIDATION_FAILED"),
                    extractMessage(res.body, "Search validation failed."),
                    "সার্চ অনুরোধে ত্রুটি হয়েছে।"
            );
        } catch (Exception netEx) {
            FastSearchResponseDto offlineHits = searchOfflineSnapshot(branchId, query);
            return new ClientOutcome<>(
                    UiState.OFFLINE,
                    offlineHits,
                    "OFFLINE_SNAPSHOT_ESTIMATE",
                    "Offline mode: showing cached SKU snapshot estimates (cloud stock unverified).",
                    "অফলাইন মোড: লোকাল স্ন্যাপশট ক্যাশ থেকে সম্ভাব্য তথ্য দেখানো হচ্ছে।"
            );
        }
    }

    public ClientOutcome<List<FastSearchCandidateDto>> refreshLocalSnapshotCache(String branchId, String token) {
        try {
            String encB = URLEncoder.encode(branchId != null ? branchId : "", StandardCharsets.UTF_8);
            ApiResponseResult res = transport.send("GET", "/api/v2/pos/search/snapshot-cache/?branch=" + encB, "", token);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                Map<?, ?> parsed = parseMap(res.body);
                List<FastSearchCandidateDto> loaded = new ArrayList<>();
                Object itemsObj = parsed.get("items");
                if (itemsObj instanceof List<?> list) {
                    for (Object obj : list) {
                        if (obj instanceof Map<?, ?> m) {
                            loaded.add(FastSearchCandidateDto.fromSnapshotItemMap(m));
                        }
                    }
                }
                if (branchId != null) {
                    branchSnapshotCache.put(branchId, loaded);
                }
                return new ClientOutcome<>(
                        loaded.isEmpty() ? UiState.EMPTY : UiState.READY,
                        loaded,
                        "",
                        "Cached " + loaded.size() + " validated branch SKUs for offline POS fallback.",
                        loaded.size() + "টি ভ্যালিডেটেড SKU অফলাইন ব্যবহারের জন্য ক্যাশ হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, List.of(), "ERR_PERMISSION_DENIED",
                        UiState.PERMISSION_DENIED.defaultMessageEn, UiState.PERMISSION_DENIED.defaultMessageBn);
            }
            return new ClientOutcome<>(UiState.ERROR, List.of(), "ERR_SNAPSHOT_LOAD",
                    extractMessage(res.body, "Failed to load SKU snapshot cache."), "SKU স্ন্যাপশট লোড ব্যর্থ হয়েছে।");
        } catch (Exception ex) {
            return new ClientOutcome<>(UiState.OFFLINE, getCachedSnapshot(branchId), "OFFLINE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn);
        }
    }

    public ClientOutcome<PosSaleOrderDto> openQuickCart(String branchId, String offlineDeviceNamespace, String token) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("branch", branchId);
            if (offlineDeviceNamespace != null && !offlineDeviceNamespace.isBlank()) {
                payload.put("offline_device_namespace", offlineDeviceNamespace);
            }
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/quick-cart/", json, token);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                Map<?, ?> parsed = parseMap(res.body);
                Object saleObj = parsed.get("sale");
                PosSaleOrderDto dto = PosSaleOrderDto.fromMap(saleObj instanceof Map<?, ?> sm ? sm : parsed);
                return new ClientOutcome<>(UiState.READY, dto, "", "Counter cart ready.", "কাউন্টার কার্ট প্রস্তুত।");
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                return new ClientOutcome<>(UiState.PERMISSION_DENIED, null, extractErrorCode(res.body, "ERR_PERMISSION_DENIED"),
                        UiState.PERMISSION_DENIED.defaultMessageEn, UiState.PERMISSION_DENIED.defaultMessageBn);
            }
            return new ClientOutcome<>(UiState.ERROR, null, extractErrorCode(res.body, "ERR_CART_OPEN"),
                    extractMessage(res.body, "Failed to open counter cart."), "কাউন্টার কার্ট খোলা যায়নি।");
        } catch (Exception netEx) {
            PosSaleOrderDto offlineCart = new PosSaleOrderDto();
            offlineCart.saleId = "offline-cart-" + UUID.randomUUID().toString().substring(0, 8);
            offlineCart.branchId = branchId != null ? branchId : "";
            offlineCart.lifecycleState = PosSaleDomainClient.LifecycleState.OFFLINE_PENDING_SYNC;
            offlineCart.syncStatus = "LOCAL_PENDING_SYNC";
            return new ClientOutcome<>(UiState.OFFLINE, offlineCart, "OFFLINE_CART",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn);
        }
    }

    public ClientOutcome<PosSaleOrderDto> scanAdd(
            String saleId,
            String barcodeOrSku,
            String productId,
            String variantId,
            String packagingUnit,
            int quantityDelta,
            Integer expectedVersion,
            String token
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (barcodeOrSku != null && !barcodeOrSku.isBlank()) payload.put("barcode", barcodeOrSku.trim());
            if (productId != null && !productId.isBlank()) payload.put("product_id", productId.trim());
            if (variantId != null && !variantId.isBlank()) payload.put("variant_id", variantId.trim());
            if (packagingUnit != null && !packagingUnit.isBlank()) payload.put("packaging_unit", packagingUnit.trim());
            payload.put("quantity_delta", Math.max(1, quantityDelta));
            if (expectedVersion != null) payload.put("expected_version", expectedVersion);

            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/" + saleId + "/scan-add/", json, token);
            return handleCartMutationResponse(res);
        } catch (Exception netEx) {
            return new ClientOutcome<>(UiState.OFFLINE, null, "OFFLINE",
                    "Cloud cart unavailable offline; apply item locally using cached snapshot.",
                    "অফলাইন মোড: লোকাল ক্যাশড স্ন্যাপশট থেকে কার্টে যুক্ত করুন।");
        }
    }

    public ClientOutcome<PosSaleOrderDto> mutateCartLine(
            String saleId,
            String lineId,
            Integer delta,
            Integer quantityUnits,
            String packagingUnit,
            boolean remove,
            Integer expectedVersion,
            String token
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("line_id", lineId);
            if (delta != null) payload.put("delta", delta);
            if (quantityUnits != null) payload.put("quantity_units", quantityUnits);
            if (packagingUnit != null && !packagingUnit.isBlank()) payload.put("packaging_unit", packagingUnit);
            if (remove) payload.put("remove", true);
            if (expectedVersion != null) payload.put("expected_version", expectedVersion);

            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("PATCH", "/api/v2/pos/sales/" + saleId + "/cart-line/", json, token);
            return handleCartMutationResponse(res);
        } catch (Exception netEx) {
            return new ClientOutcome<>(UiState.OFFLINE, null, "OFFLINE",
                    UiState.OFFLINE.defaultMessageEn, UiState.OFFLINE.defaultMessageBn);
        }
    }

    public ClientOutcome<UnitOptionsResponseDto> fetchUnitOptions(
            String saleId,
            String lineId,
            String branchId,
            String productId,
            String variantId,
            String token
    ) {
        try {
            String path;
            if (saleId != null && !saleId.isBlank() && !saleId.startsWith("offline-cart-")) {
                StringBuilder sb = new StringBuilder("/api/v2/pos/sales/")
                        .append(URLEncoder.encode(saleId, StandardCharsets.UTF_8))
                        .append("/unit-options/?");
                if (lineId != null && !lineId.isBlank()) {
                    sb.append("line_id=").append(URLEncoder.encode(lineId, StandardCharsets.UTF_8)).append("&");
                }
                if (productId != null && !productId.isBlank()) {
                    sb.append("product_id=").append(URLEncoder.encode(productId, StandardCharsets.UTF_8));
                }
                path = sb.toString();
            } else {
                StringBuilder sb = new StringBuilder("/api/v2/pos/search/unit-options/?branch=")
                        .append(URLEncoder.encode(branchId != null ? branchId : "", StandardCharsets.UTF_8))
                        .append("&product_id=")
                        .append(URLEncoder.encode(productId != null ? productId : "", StandardCharsets.UTF_8));
                if (variantId != null && !variantId.isBlank()) {
                    sb.append("&variant_id=").append(URLEncoder.encode(variantId, StandardCharsets.UTF_8));
                }
                path = sb.toString();
            }

            ApiResponseResult res = transport.send("GET", path, "", token);
            if (res.statusCode >= 200 && res.statusCode < 300) {
                UnitOptionsResponseDto dto = UnitOptionsResponseDto.fromMap(parseMap(res.body));
                return new ClientOutcome<>(
                        dto.packagingOptions.isEmpty() ? UiState.EMPTY : UiState.READY,
                        dto,
                        "",
                        "Loaded packaging unit options.",
                        "প্যাকেজিং ইউনিট বিকল্প লোড হয়েছে।"
                );
            }
            if (res.statusCode == 401 || res.statusCode == 403) {
                return new ClientOutcome<>(
                        UiState.PERMISSION_DENIED,
                        null,
                        extractErrorCode(res.body, "ERR_PERMISSION_DENIED"),
                        extractMessage(res.body, UiState.PERMISSION_DENIED.defaultMessageEn),
                        extractMessageBn(res.body, UiState.PERMISSION_DENIED.defaultMessageBn)
                );
            }
            return new ClientOutcome<>(
                    UiState.ERROR,
                    null,
                    extractErrorCode(res.body, "ERR_UNIT_OPTIONS_FAILED"),
                    extractMessage(res.body, "Failed to load packaging unit options."),
                    extractMessageBn(res.body, "প্যাকেজিং ইউনিট অপশন লোড করা যায়নি।")
            );
        } catch (Exception netEx) {
            return new ClientOutcome<>(
                    UiState.OFFLINE,
                    null,
                    "OFFLINE",
                    "Offline mode: using cached local snapshot conversion factors.",
                    "অফলাইন মোড: লোকাল স্ন্যাপশট কনভার্সন ফ্যাক্টর ব্যবহৃত হচ্ছে।"
            );
        }
    }

    public ClientOutcome<PosSaleOrderDto> selectCartLineUnit(
            String saleId,
            String lineId,
            String packagingUnit,
            Integer quantityUnits,
            Integer conversionFactor,
            String conversionMode,
            Integer expectedVersion,
            String overrideReason,
            String token
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (lineId != null && !lineId.isBlank()) payload.put("line_id", lineId);
            payload.put("packaging_unit", packagingUnit != null ? packagingUnit.trim().toUpperCase(Locale.ROOT) : "");
            if (quantityUnits != null) payload.put("quantity_units", quantityUnits);
            if (conversionFactor != null) payload.put("conversion_factor", conversionFactor);
            if (conversionMode != null && !conversionMode.isBlank()) {
                payload.put("conversion_mode", conversionMode.trim().toUpperCase(Locale.ROOT));
            }
            if (expectedVersion != null) payload.put("expected_version", expectedVersion);
            if (overrideReason != null && !overrideReason.isBlank()) {
                payload.put("override_reason", overrideReason.trim());
            }
            String json = mapper.writeValueAsString(payload);
            ApiResponseResult res = transport.send("POST", "/api/v2/pos/sales/" + saleId + "/select-unit/", json, token);
            return handleCartMutationResponse(res);
        } catch (Exception netEx) {
            return new ClientOutcome<>(
                    UiState.OFFLINE,
                    null,
                    "OFFLINE",
                    UiState.OFFLINE.defaultMessageEn,
                    UiState.OFFLINE.defaultMessageBn
            );
        }
    }

    private ClientOutcome<PosSaleOrderDto> handleCartMutationResponse(ApiResponseResult res) throws Exception {
        if (res.statusCode >= 200 && res.statusCode < 300) {
            Map<?, ?> parsed = parseMap(res.body);
            Object saleObj = parsed.get("sale");
            PosSaleOrderDto dto = PosSaleOrderDto.fromMap(saleObj instanceof Map<?, ?> sm ? sm : parsed);
            return new ClientOutcome<>(
                    dto.lines.isEmpty() ? UiState.EMPTY : UiState.READY,
                    dto,
                    "",
                    "Cart updated (" + dto.lines.size() + " lines).",
                    "কার্ট আপডেট হয়েছে (" + dto.lines.size() + " লাইন)।"
            );
        }
        if (res.statusCode == 401 || res.statusCode == 403) {
            return new ClientOutcome<>(UiState.PERMISSION_DENIED, null,
                    extractErrorCode(res.body, "ERR_PERMISSION_DENIED"),
                    extractMessage(res.body, UiState.PERMISSION_DENIED.defaultMessageEn),
                    extractMessageBn(res.body, UiState.PERMISSION_DENIED.defaultMessageBn));
        }
        return new ClientOutcome<>(UiState.ERROR, null,
                extractErrorCode(res.body, "ERR_CART_MUTATION"),
                extractMessage(res.body, "Unable to update cart item."),
                extractMessageBn(res.body, "কার্ট লাইন আপডেট করা যায়নি।"));
    }

    private static FastSearchCandidateDto cloneForOffline(FastSearchCandidateDto src, int score) {
        FastSearchCandidateDto copy = new FastSearchCandidateDto();
        copy.productId = src.productId;
        copy.variantId = src.variantId;
        copy.skuCode = src.skuCode;
        copy.displayName = src.displayName;
        copy.brandName = src.brandName;
        copy.brandNameBn = src.brandNameBn;
        copy.genericName = src.genericName;
        copy.genericNameBn = src.genericNameBn;
        copy.strength = src.strength;
        copy.dosageForm = src.dosageForm;
        copy.barcode = src.barcode;
        copy.shelfLocation = src.shelfLocation;
        copy.resolutionSource = "OFFLINE_SNAPSHOT";
        copy.resolvedPackagingUnit = src.resolvedPackagingUnit;
        copy.resolvedConversionFactor = src.resolvedConversionFactor;
        copy.unitPriceBdt = src.unitPriceBdt;
        copy.baseUnitPriceBdt = src.baseUnitPriceBdt;
        copy.sellableBaseUnits = src.sellableBaseUnits;
        copy.reservedBaseUnits = src.reservedBaseUnits;
        copy.availableBaseUnits = src.availableBaseUnits;
        copy.quarantinedBaseUnits = src.quarantinedBaseUnits;
        copy.expiredBaseUnits = src.expiredBaseUnits;
        copy.recalledBaseUnits = src.recalledBaseUnits;
        copy.earliestBatchId = src.earliestBatchId;
        copy.earliestBatchNumber = src.earliestBatchNumber;
        copy.earliestExpiryDate = src.earliestExpiryDate;
        copy.nearExpiryWarning = src.nearExpiryWarning;
        copy.requiresRxVerification = src.requiresRxVerification;
        copy.nonDivisibleSealedOnly = src.nonDivisibleSealedOnly;
        copy.isSellable = src.isSellable;
        copy.blockReason = src.blockReason;
        copy.snapshotReadModelId = src.snapshotReadModelId;
        copy.snapshotChecksum = src.snapshotChecksum;
        copy.snapshotAsOf = src.snapshotAsOf;
        copy.rankScore = score;
        copy.conversionSource = src.conversionSource;
        copy.blockedUnits.addAll(src.blockedUnits);
        copy.packagingOptions.addAll(src.packagingOptions);
        return copy;
    }

    private Map<?, ?> parseMap(String body) throws Exception {
        if (body == null || body.isBlank()) return Map.of();
        return mapper.readValue(body, Map.class);
    }

    private String extractErrorCode(String body, String fallback) {
        try {
            Map<?, ?> m = parseMap(body);
            Object c = m.get("error_code");
            if (c != null && !c.toString().isBlank()) return c.toString();
            Object det = m.get("details");
            if (det instanceof Map<?, ?> dm && dm.get("error_code") != null) return dm.get("error_code").toString();
        } catch (Exception ignored) {}
        return fallback;
    }

    private String extractMessage(String body, String fallback) {
        try {
            Map<?, ?> m = parseMap(body);
            Object msg = m.get("message_en");
            if (msg == null) msg = m.get("message");
            if (msg == null) msg = m.get("packaging_unit");
            if (msg == null) msg = m.get("quantity_units");
            if (msg == null) msg = m.get("detail");
            if (msg != null && !msg.toString().isBlank()) return msg.toString();
        } catch (Exception ignored) {}
        return fallback;
    }

    private String extractMessageBn(String body, String fallback) {
        try {
            Map<?, ?> m = parseMap(body);
            Object msg = m.get("message_bn");
            if (msg != null && !msg.toString().isBlank()) return msg.toString();
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static boolean containsToken(String tokens, String target) {
        if (tokens == null || tokens.isBlank() || target == null || target.isBlank()) return false;
        for (String t : tokens.split("\\s+")) {
            if (t.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String strOr(Object o, String fallback) {
        String s = str(o);
        return s.isEmpty() ? fallback : s;
    }

    private static int integer(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? fallback : Integer.parseInt(o.toString());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BigDecimal dec2(Object o) {
        try {
            return o == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : new BigDecimal(o.toString()).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static BigDecimal dec4(Object o) {
        try {
            return o == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                    : new BigDecimal(o.toString()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
    }
}

