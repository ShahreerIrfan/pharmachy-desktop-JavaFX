package com.pharmacy.pos.security;

import com.pharmacy.pos.contracts.PosApiResponseEnvelope.UiState;
import com.pharmacy.pos.models.PosStoredAttachment;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JavaFX Desktop POS Private File Storage & Antivirus Policy Service.
 * Architecture Decision: ADR-0051 (Private File Storage, Antivirus & Expiring URLs).
 * Manages pre-upload client-side inspection, category permissions, signed URL expiration,
 * and bilingual error handling.
 */
public class PosFileStorageService {

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".jpg", ".jpeg", ".png", ".webp"
    );

    public static final Set<String> BLOCKED_EXECUTABLE_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".js", ".php", ".jar", ".msi"
    );

    public static final long MAX_FILE_SIZE_BYTES = 15 * 1024 * 1024; // 15MB

    /**
     * Pre-validates file before initiating network upload.
     */
    public static boolean validateUpload(String filename, long fileSizeBytes) {
        if (filename == null || filename.isBlank()) return false;
        if (fileSizeBytes <= 0 || fileSizeBytes > MAX_FILE_SIZE_BYTES) return false;

        String lower = filename.toLowerCase();
        for (String blocked : BLOCKED_EXECUTABLE_EXTENSIONS) {
            if (lower.endsWith(blocked)) return false;
        }

        for (String allowed : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(allowed)) return true;
        }

        return false;
    }

    /**
     * Scoped Role-Based Access Control for file categories conforming to ADR-0005 & ADR-0051.
     */
    public static boolean isAuthorizedForCategory(String userRole, String category) {
        if (userRole == null || category == null) return false;
        String role = userRole.toUpperCase();

        if ("PRESCRIPTION_IMAGE".equalsIgnoreCase(category)) {
            // Zero-PHI Protection: Cashiers and SuperAdmins strictly forbidden
            if ("CASHIER".equals(role) || "SUPERADMIN".equals(role) || "PLATFORM_OPERATOR".equals(role)) {
                return false;
            }
            return Set.of("PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "AUDITOR").contains(role);
        }

        if ("SALES_INVOICE_PDF".equalsIgnoreCase(category)) {
            return Set.of("PHARMACY_OWNER", "BRANCH_MANAGER", "FINANCE_OFFICER", "CASHIER", "AUDITOR").contains(role);
        }

        if ("DGDA_COMPLIANCE".equalsIgnoreCase(category)) {
            return Set.of("PHARMACY_OWNER", "BRANCH_MANAGER", "AUDITOR", "SUPERADMIN").contains(role);
        }

        return Set.of("PHARMACY_OWNER", "BRANCH_MANAGER", "STAFF_PHARMACIST", "AUDITOR").contains(role);
    }

    /**
     * Checks if a signed download URL timestamp has expired.
     */
    public static boolean isUrlExpired(String expiresAtIso) {
        if (expiresAtIso == null || expiresAtIso.isBlank()) return true;
        try {
            Instant expiry = Instant.parse(expiresAtIso);
            return Instant.now().isAfter(expiry);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Parses server response JSON into PosStoredAttachment DTO.
     */
    public static PosStoredAttachment parseStoredAttachment(Map<String, Object> map) {
        Objects.requireNonNull(map, "Stored attachment map cannot be null");

        String fileId = (String) map.get("file_id");
        String tenantId = (String) map.get("tenant_id");
        String branchId = (String) map.get("branch_id");
        String fileName = (String) map.get("file_name");
        String category = (String) map.get("category");
        String mimeType = (String) map.get("mime_type");
        long fileSizeBytes = map.get("file_size_bytes") instanceof Number n ? n.longValue() : 0L;
        String checksumSha256 = (String) map.get("checksum_sha256");
        String scanStatus = (String) map.getOrDefault("scan_status", "PENDING_SCAN");
        boolean isQuarantined = Boolean.TRUE.equals(map.get("is_quarantined"));
        String quarantineReason = (String) map.getOrDefault("quarantine_reason", "");
        String signedDownloadUrl = (String) map.get("signed_download_url");
        String expiresAt = (String) map.get("expires_at");
        String createdAt = (String) map.get("created_at");

        return new PosStoredAttachment(
                fileId,
                tenantId,
                branchId,
                fileName,
                category,
                mimeType,
                fileSizeBytes,
                checksumSha256,
                scanStatus,
                isQuarantined,
                quarantineReason,
                signedDownloadUrl,
                expiresAt,
                createdAt
        );
    }

    /**
     * Resolves localized bilingual error message conforming to ADR-0014 & ADR-0051.
     */
    public static Map<String, String> getBilingualErrorMessage(String errorCode) {
        Map<String, String> messages = new HashMap<>();
        if ("ERR_STORAGE_URL_EXPIRED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "The secure download link has expired. Please refresh the signed URL.");
            messages.put("bn", "ডাউনলোড লিঙ্কের মেয়াদ শেষ হয়ে গেছে। অনুগ্রহ করে নতুন স্বাক্ষরিত লিঙ্কের অনুরোধ করুন।");
        } else if ("ERR_FILE_QUARANTINED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "File blocked: Antivirus policy quarantined this attachment due to security risks.");
            messages.put("bn", "ফাইল ব্লক করা হয়েছে: অ্যান্টিভাইরাস পলিসি দ্বারা ফাইলটি কোয়ারেন্টাইন করা হয়েছে।");
        } else if ("ERR_INVALID_FILE_TYPE".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Disallowed file type or executable content detected. Only PDF, JPEG, PNG, and WebP are permitted.");
            messages.put("bn", "নিষিদ্ধ ফাইল ফরম্যাট শনাক্ত হয়েছে। শুধুমাত্র PDF, JPEG, PNG, এবং WebP ফাইল অনুমোদিত।");
        } else if ("ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Cross-tenant file access is strictly forbidden.");
            messages.put("bn", "অন্য ফার্মেসির ফাইলে প্রবেশাধিকার কঠোরভাবে নিষিদ্ধ।");
        } else if ("ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
            messages.put("en", "Access denied: You do not have permission to view or download this attachment.");
            messages.put("bn", "প্রবেশাধিকার প্রত্যাখ্যাত: এই ফাইলটি দেখার বা ডাউনলোড করার অনুমতি আপনার নেই।");
        } else {
            messages.put("en", "Secure storage operation failed. Please verify connection.");
            messages.put("bn", "ফাইল স্টোরেজ পরিচালনায় ত্রুটি দেখা দিয়েছে। সংযোগ পরীক্ষা করুন।");
        }
        return Collections.unmodifiableMap(messages);
    }

    /**
     * Resolves UI State for JavaFX controllers.
     */
    public static UiState resolveUiState(String errorCode) {
        if ("ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)
                || "ERR_CROSS_TENANT_VIOLATION".equalsIgnoreCase(errorCode)
                || "ERR_FILE_QUARANTINED".equalsIgnoreCase(errorCode)) {
            return UiState.PERMISSION_DENIED;
        }
        return UiState.ERROR;
    }
}

