package com.pharmacy.pos.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PosMedicineFormTemplateClient
 * Architecture Decision: ADR-0131 (P131).
 * Phase 07: Milestone M04 - Shared Medicine Catalog & Dynamic Packaging Units.
 *
 * JavaFX Desktop client for Dosage-Form Medicine Form Templates, Layout Rules,
 * Allowed Clinical & Packaging Values, and Offline Cache Resilience.
 */
public class PosMedicineFormTemplateClient {

    public enum UiStatus {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        EMPTY,
        OFFLINE
    }

    public static final class FieldWidgetConfig {
        public final String fieldKey;
        public final String widgetType; // text_input, number_stepper, decimal_input, dropdown_select, checkbox_toggle
        public final int columnSpan;
        public final String labelEn;
        public final String labelBn;
        public final String placeholderEn;
        public final String placeholderBn;
        public final boolean isRequired;
        public final boolean isReadOnly;
        public final Object defaultValue;

        public FieldWidgetConfig(
                String fieldKey,
                String widgetType,
                int columnSpan,
                String labelEn,
                String labelBn,
                String placeholderEn,
                String placeholderBn,
                boolean isRequired,
                boolean isReadOnly,
                Object defaultValue) {
            this.fieldKey = fieldKey;
            this.widgetType = widgetType;
            this.columnSpan = columnSpan;
            this.labelEn = labelEn;
            this.labelBn = labelBn;
            this.placeholderEn = placeholderEn;
            this.placeholderBn = placeholderBn;
            this.isRequired = isRequired;
            this.isReadOnly = isReadOnly;
            this.defaultValue = defaultValue;
        }
    }

    public static final class LayoutSection {
        public final String sectionId;
        public final String titleEn;
        public final String titleBn;
        public final boolean collapsible;
        public final boolean defaultExpanded;
        public final int displayOrder;
        public final List<FieldWidgetConfig> fields;

        public LayoutSection(
                String sectionId,
                String titleEn,
                String titleBn,
                boolean collapsible,
                boolean defaultExpanded,
                int displayOrder,
                List<FieldWidgetConfig> fields) {
            this.sectionId = sectionId;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.collapsible = collapsible;
            this.defaultExpanded = defaultExpanded;
            this.displayOrder = displayOrder;
            this.fields = fields != null ? Collections.unmodifiableList(fields) : Collections.emptyList();
        }
    }

    public static final class LayoutRules {
        public final int gridColumns;
        public final List<LayoutSection> sections;

        public LayoutRules(int gridColumns, List<LayoutSection> sections) {
            this.gridColumns = gridColumns;
            this.sections = sections != null ? Collections.unmodifiableList(sections) : Collections.emptyList();
        }
    }

    public static final class AllowedValues {
        public final List<String> strengthUnits;
        public final List<String> administrationRoutes;
        public final List<String> containerTypes;
        public final List<String> storageConditions;
        public final List<String> dispensingUnits;

        public AllowedValues(
                List<String> strengthUnits,
                List<String> administrationRoutes,
                List<String> containerTypes,
                List<String> storageConditions,
                List<String> dispensingUnits) {
            this.strengthUnits = strengthUnits != null ? Collections.unmodifiableList(strengthUnits) : Collections.emptyList();
            this.administrationRoutes = administrationRoutes != null ? Collections.unmodifiableList(administrationRoutes) : Collections.emptyList();
            this.containerTypes = containerTypes != null ? Collections.unmodifiableList(containerTypes) : Collections.emptyList();
            this.storageConditions = storageConditions != null ? Collections.unmodifiableList(storageConditions) : Collections.emptyList();
            this.dispensingUnits = dispensingUnits != null ? Collections.unmodifiableList(dispensingUnits) : Collections.emptyList();
        }
    }

    public static final class MedicineFormTemplate {
        public final String templateId;
        public final String dosageFormCode;
        public final String dosageFormNameEn;
        public final String dosageFormNameBn;
        public final String templateCode;
        public final String templateVersion;
        public final String titleEn;
        public final String titleBn;
        public final String description;
        public final boolean isActive;
        public final boolean isDefault;
        public final LayoutRules layoutRules;
        public final AllowedValues allowedValues;
        public final String immutableVersionHash;
        public final Instant createdAt;

        public MedicineFormTemplate(
                String templateId,
                String dosageFormCode,
                String dosageFormNameEn,
                String dosageFormNameBn,
                String templateCode,
                String templateVersion,
                String titleEn,
                String titleBn,
                String description,
                boolean isActive,
                boolean isDefault,
                LayoutRules layoutRules,
                AllowedValues allowedValues,
                String immutableVersionHash,
                Instant createdAt) {
            this.templateId = templateId;
            this.dosageFormCode = dosageFormCode;
            this.dosageFormNameEn = dosageFormNameEn;
            this.dosageFormNameBn = dosageFormNameBn;
            this.templateCode = templateCode;
            this.templateVersion = templateVersion;
            this.titleEn = titleEn;
            this.titleBn = titleBn;
            this.description = description;
            this.isActive = isActive;
            this.isDefault = isDefault;
            this.layoutRules = layoutRules;
            this.allowedValues = allowedValues;
            this.immutableVersionHash = immutableVersionHash;
            this.createdAt = createdAt;
        }
    }

    public static final class ClientResult<T> {
        public final UiStatus status;
        public final T data;
        public final String errorCode;
        public final String messageEn;
        public final String messageBn;

        private ClientResult(UiStatus status, T data, String errorCode, String messageEn, String messageBn) {
            this.status = status;
            this.data = data;
            this.errorCode = errorCode;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
        }

        public static <T> ClientResult<T> success(T data) {
            return new ClientResult<>(UiStatus.SUCCESS, data, null, "Operation completed successfully.", "অপারেশন সফলভাবে সম্পন্ন হয়েছে।");
        }

        public static <T> ClientResult<T> offline(T data) {
            return new ClientResult<>(UiStatus.OFFLINE, data, "STATUS_OFFLINE_CACHE", "Served from local offline template cache.", "লোকাল অফলাইন টেমপ্লেট ক্যাশ থেকে পরিবেশিত হচ্ছে।");
        }

        public static <T> ClientResult<T> permissionDenied(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.PERMISSION_DENIED, null, code, en, bn);
        }

        public static <T> ClientResult<T> error(String code, String en, String bn) {
            return new ClientResult<>(UiStatus.ERROR, null, code, en, bn);
        }

        public static <T> ClientResult<T> empty(String en, String bn) {
            return new ClientResult<>(UiStatus.EMPTY, null, "ERR_TEMPLATE_NOT_FOUND", en, bn);
        }
    }

    private final Map<String, MedicineFormTemplate> templateRegistry = new ConcurrentHashMap<>();
    private final Map<String, MedicineFormTemplate> offlineTemplateCache = new ConcurrentHashMap<>();
    private boolean isOffline = false;

    public PosMedicineFormTemplateClient() {
        seedCanonicalTemplates();
    }

    public void setOfflineMode(boolean offline) {
        this.isOffline = offline;
    }

    public boolean isOfflineMode() {
        return this.isOffline;
    }

    public static String computeImmutableHash(
            String dosageFormCode,
            String templateCode,
            String templateVersion,
            int gridColumns,
            List<String> strengthUnits) {
        try {
            String raw = String.format("%s|%s|%s|%d|%s",
                    dosageFormCode.toUpperCase().trim(),
                    templateCode.toUpperCase().trim(),
                    templateVersion.trim(),
                    gridColumns,
                    String.join(",", strengthUnits));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private void seedCanonicalTemplates() {
        // 1. Tablet Template
        List<FieldWidgetConfig> tabFields1 = Arrays.asList(
                new FieldWidgetConfig("brand_name", "text_input", 6, "Brand Name", "ব্র্যান্ডের নাম", "e.g. Napa 500mg", "যেমন: নাপা ৫০০ মিগ্রা", true, false, ""),
                new FieldWidgetConfig("generic_name", "text_input", 6, "Generic Name", "জেনেরিক নাম", "e.g. Paracetamol", "যেমন: প্যারাসিটামল", true, false, "")
        );
        List<FieldWidgetConfig> tabFields2 = Arrays.asList(
                new FieldWidgetConfig("box_size", "number_stepper", 4, "Box Units", "বক্স প্রতি ট্যাবলেট", "100", "১০০", true, false, 100),
                new FieldWidgetConfig("strips_per_box", "number_stepper", 4, "Strips Per Box", "বক্স প্রতি পাতা", "10", "১০", true, false, 10),
                new FieldWidgetConfig("units_per_strip", "number_stepper", 4, "Tablets Per Strip", "পাতা প্রতি ট্যাবলেট", "10", "১০", true, false, 10)
        );
        LayoutRules tabLayout = new LayoutRules(12, Arrays.asList(
                new LayoutSection("identification", "Product Identification", "ওষুধের প্রাথমিক পরিচিতি", false, true, 1, tabFields1),
                new LayoutSection("packaging_hierarchy", "Packaging Unit Conversion", "প্যাকেজিং রূপান্তর স্তরবিন্যাস", true, true, 2, tabFields2)
        ));
        AllowedValues tabAllowed = new AllowedValues(
                Arrays.asList("mg", "mcg", "g"),
                Arrays.asList("Oral", "Sublingual", "Chewable", "Dispersible"),
                Arrays.asList("STRIP", "BLISTER_PACK", "ALU_ALU", "BOTTLE"),
                Arrays.asList("ROOM_TEMPERATURE", "COOL_DRY_PLACE"),
                Arrays.asList("Tablet", "Piece", "Pcs")
        );
        String tabHash = computeImmutableHash("TABLET", "TABLET_STANDARD", "v1.0", 12, tabAllowed.strengthUnits);
        MedicineFormTemplate tabTemplate = new MedicineFormTemplate(
                "tmpl-tablet-v1",
                "TABLET",
                "Tablet",
                "ট্যাবলেট",
                "TABLET_STANDARD",
                "v1.0",
                "Standard Solid Oral Tablet Form Template",
                "স্ট্যান্ডার্ড সলিড ট্যাবলেট ফরম টেমপ্লেট",
                "Layout for solid oral tablets with box/strip/unit packaging hierarchy.",
                true,
                true,
                tabLayout,
                tabAllowed,
                tabHash,
                Instant.now()
        );
        registerTemplate(tabTemplate);

        // 2. Syrup Template
        List<FieldWidgetConfig> syrFields1 = Arrays.asList(
                new FieldWidgetConfig("volume_ml", "decimal_input", 4, "Bottle Volume (mL)", "বোতলের আয়তন (মি.লি.)", "100", "১০০", true, false, 100.0),
                new FieldWidgetConfig("strength_numeric", "decimal_input", 4, "Concentration Value", "ঘনমাত্রার মান", "120", "১২০", true, false, 120.0),
                new FieldWidgetConfig("strength_unit", "dropdown_select", 4, "Concentration Unit", "ঘনমাত্রার একক", "mg/5mL", "মিগ্রা/৫মি.লি.", true, false, "mg/5mL")
        );
        LayoutRules syrLayout = new LayoutRules(12, Collections.singletonList(
                new LayoutSection("liquid_volume_spec", "Liquid Volume & Concentration", "তরল আয়তন ও ঘনমাত্রা", false, true, 1, syrFields1)
        ));
        AllowedValues syrAllowed = new AllowedValues(
                Arrays.asList("mg/5mL", "mcg/5mL", "g/100mL", "%"),
                Collections.singletonList("Oral"),
                Arrays.asList("GLASS_BOTTLE", "PET_BOTTLE", "DROPS_BOTTLE"),
                Arrays.asList("ROOM_TEMPERATURE", "SHAKE_WELL_BEFORE_USE"),
                Arrays.asList("Bottle", "Phial")
        );
        String syrHash = computeImmutableHash("SYRUP", "SYRUP_LIQUID", "v1.0", 12, syrAllowed.strengthUnits);
        MedicineFormTemplate syrTemplate = new MedicineFormTemplate(
                "tmpl-syrup-v1",
                "SYRUP",
                "Syrup",
                "সিরাপ",
                "SYRUP_LIQUID",
                "v1.0",
                "Oral Liquid Syrup / Suspension Form Template",
                "ওরাল লিকুইড সিরাপ ও সাসপেনশন ফরম টেমপ্লেট",
                "Liquid layout emphasizing volume in milliliters and concentration units.",
                true,
                true,
                syrLayout,
                syrAllowed,
                syrHash,
                Instant.now()
        );
        registerTemplate(syrTemplate);

        // 3. Injection Template
        List<FieldWidgetConfig> injFields1 = Arrays.asList(
                new FieldWidgetConfig("is_cold_chain", "checkbox_toggle", 4, "Cold Chain Required", "কোল্ড চেইন আবশ্যক", "", "", true, false, true),
                new FieldWidgetConfig("min_temperature_celsius", "decimal_input", 4, "Min Temp (°C)", "সর্বনিম্ন তাপমাত্রা (°সে.)", "2.0", "২.০", true, false, 2.0),
                new FieldWidgetConfig("max_temperature_celsius", "decimal_input", 4, "Max Temp (°C)", "সর্বোচ্চ তাপমাত্রা (°সে.)", "8.0", "৮.০", true, false, 8.0)
        );
        LayoutRules injLayout = new LayoutRules(12, Collections.singletonList(
                new LayoutSection("cold_chain_spec", "Cold Chain & Storage Thresholds", "কোল্ড চেইন ও তাপমাত্রা নিয়ন্ত্রণ", false, true, 1, injFields1)
        ));
        AllowedValues injAllowed = new AllowedValues(
                Arrays.asList("mg/mL", "IU/mL", "mcg/mL", "%"),
                Arrays.asList("Intravenous (IV)", "Intramuscular (IM)", "Subcutaneous (SC)"),
                Arrays.asList("VIAL", "AMPOULE", "PRE_FILLED_SYRINGE"),
                Arrays.asList("COLD_CHAIN_2_8C", "DEEP_FREEZE", "ROOM_TEMPERATURE"),
                Arrays.asList("Vial", "Ampoule")
        );
        String injHash = computeImmutableHash("INJECTION", "INJECTION_STERILE", "v1.0", 12, injAllowed.strengthUnits);
        MedicineFormTemplate injTemplate = new MedicineFormTemplate(
                "tmpl-injection-v1",
                "INJECTION",
                "Injection",
                "ইনজেকশন",
                "INJECTION_STERILE",
                "v1.0",
                "Sterile Parenteral Injection Form Template",
                "স্টেরাইল প্যারেন্টেরাল ইনজেকশন ফরম টেমপ্লেট",
                "Parenteral injection layout enforcing cold chain thresholds and vial/ampoule packaging.",
                true,
                true,
                injLayout,
                injAllowed,
                injHash,
                Instant.now()
        );
        registerTemplate(injTemplate);
    }

    public void registerTemplate(MedicineFormTemplate template) {
        if (template != null) {
            templateRegistry.put(template.templateId, template);
            templateRegistry.put(template.dosageFormCode.toUpperCase(), template);
            templateRegistry.put(template.templateCode.toUpperCase(), template);
            offlineTemplateCache.put(template.dosageFormCode.toUpperCase(), template);
        }
    }

    /**
     * Resolves form template by template_id, template_code, or dosage_form_code.
     */
    public ClientResult<MedicineFormTemplate> getTemplate(String identifier) {
        String clean = identifier != null ? identifier.trim().toUpperCase() : "";

        if (isOffline) {
            MedicineFormTemplate cached = offlineTemplateCache.get(clean);
            if (cached != null) {
                return ClientResult.offline(cached);
            }
            return ClientResult.empty(
                    "No cached template available offline for '" + identifier + "'.",
                    "অফলাইনে '" + identifier + "'-এর কোনো ক্যাশ টেমপ্লেট পাওয়া যায়নি।"
            );
        }

        MedicineFormTemplate tmpl = templateRegistry.get(clean);
        if (tmpl != null) {
            return ClientResult.success(tmpl);
        }

        return ClientResult.empty(
                "No template found for dosage form or template code '" + identifier + "'.",
                "'" + identifier + "'-এর জন্য কোনো ফর্ম টেমপ্লেট পাওয়া যায়নি।"
        );
    }

    /**
     * Returns allowed values for a dosage form.
     */
    public ClientResult<AllowedValues> getAllowedValues(String dosageFormCode) {
        ClientResult<MedicineFormTemplate> tmplRes = getTemplate(dosageFormCode);
        if (tmplRes.data != null) {
            return ClientResult.success(tmplRes.data.allowedValues);
        }
        return ClientResult.empty(
                "No allowed values found for dosage form '" + dosageFormCode + "'.",
                "ডোজ-ফর্ম '" + dosageFormCode + "'-এর জন্য কোনো অনুমোদিত মান পাওয়া যায়নি।"
        );
    }

    /**
     * Platform Superadmin / Operator creation of a new template.
     */
    public synchronized ClientResult<MedicineFormTemplate> createTemplate(
            String actorRole,
            String dosageFormCode,
            String templateCode,
            String templateVersion,
            String titleEn,
            String titleBn,
            String description,
            boolean isDefault,
            LayoutRules layoutRules,
            AllowedValues allowedValues) {

        String cleanRole = actorRole != null ? actorRole.trim().toUpperCase() : "";
        if (!"SUPERADMIN".equals(cleanRole) && !"SUPER_ADMIN".equals(cleanRole) && !"PLATFORM_OPERATOR".equals(cleanRole)) {
            return ClientResult.permissionDenied(
                    "PLATFORM_CATALOG_OWNERSHIP_PROTECTED",
                    "Tenant users cannot mutate global dosage-form templates.",
                    "টেন্যান্ট ব্যবহারকারীরা গ্লোবাল ডোজ-ফর্ম টেমপ্লেট তৈরি বা পরিবর্তন করতে পারবেন না।"
            );
        }

        if (dosageFormCode == null || dosageFormCode.trim().isEmpty()) {
            return ClientResult.error("ERR_INVALID_DOSAGE_FORM", "Dosage form code is required.", "ডোজ-ফর্ম কোড আবশ্যক।");
        }

        if (templateCode == null || templateCode.trim().length() < 3) {
            return ClientResult.error("ERR_INVALID_TEMPLATE_CODE", "Template code must be at least 3 characters.", "টেমপ্লেট কোড নূন্যতম ৩ অক্ষরের হতে হবে।");
        }

        if (layoutRules == null || layoutRules.sections.isEmpty()) {
            return ClientResult.error("ERR_INVALID_LAYOUT", "Layout rules must contain at least one section.", "লেআউট নিয়মে নূন্যতম একটি সেকশন থাকতে হবে।");
        }

        if (allowedValues == null || allowedValues.strengthUnits.isEmpty()) {
            return ClientResult.error("ERR_INVALID_ALLOWED_VALUES", "Allowed values must contain strength units.", "অনুমোদিত মানে মাত্রার একক থাকতে হবে।");
        }

        String hash = computeImmutableHash(dosageFormCode, templateCode, templateVersion, layoutRules.gridColumns, allowedValues.strengthUnits);
        MedicineFormTemplate tmpl = new MedicineFormTemplate(
                "tmpl-" + UUID.randomUUID().toString().substring(0, 8),
                dosageFormCode.trim().toUpperCase(),
                dosageFormCode.trim(),
                dosageFormCode.trim(),
                templateCode.trim().toUpperCase(),
                templateVersion != null ? templateVersion.trim() : "v1.0",
                titleEn != null ? titleEn.trim() : "",
                titleBn != null ? titleBn.trim() : "",
                description != null ? description.trim() : "",
                true,
                isDefault,
                layoutRules,
                allowedValues,
                hash,
                Instant.now()
        );

        registerTemplate(tmpl);
        return ClientResult.success(tmpl);
    }
}
