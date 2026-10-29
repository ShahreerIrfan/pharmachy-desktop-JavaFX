package com.pharmacy.pos.security;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Local secure secret store abstraction for JavaFX Desktop POS (ADR-0037).
 * Encapsulates tenant device tokens and offline verification keys with masked previews.
 */
public class PosSecretVault {
    private final String tenantId;
    private final String deviceId;
    private final Map<String, String> secrets;

    public PosSecretVault(String tenantId, String deviceId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId must not be null");
        this.secrets = new HashMap<>();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void storeSecret(String secretKey, String secretValue) {
        Objects.requireNonNull(secretKey, "secretKey must not be null");
        Objects.requireNonNull(secretValue, "secretValue must not be null");
        secrets.put(secretKey, secretValue);
    }

    public String getSecret(String secretKey) {
        return secrets.get(secretKey);
    }

    public boolean hasSecret(String secretKey) {
        return secrets.containsKey(secretKey);
    }

    public String getMaskedPreview(String secretKey) {
        String val = secrets.get(secretKey);
        if (val == null || val.isEmpty()) {
            return "";
        }
        if (val.length() <= 8) {
            return "********";
        }
        return val.substring(0, 4) + "-****-" + val.substring(val.length() - 4);
    }
}

