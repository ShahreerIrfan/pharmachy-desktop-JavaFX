package com.pharmacy.pos.release;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ClientVersionService
 * Architecture Decision: ADR-0038
 * Handles JavaFX POS client version inspection, SemVer 2.0.0 comparison,
 * and compatibility status formatting in English and Bengali.
 */
public class ClientVersionService {

    public static final String CURRENT_DESKTOP_VERSION = "1.2.0";
    public static final String PLATFORM = "DESKTOP";

    private static final Pattern SEMVER_PATTERN = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    );

    public enum CompatibilityStatus {
        SUPPORTED,
        DEPRECATED,
        UPGRADE_REQUIRED,
        UNKNOWN
    }

    public static final class SemVer implements Comparable<SemVer> {
        public final int major;
        public final int minor;
        public final int patch;
        public final String prerelease;

        public SemVer(int major, int minor, int patch, String prerelease) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.prerelease = prerelease;
        }

        public static SemVer parse(String versionStr) {
            if (versionStr == null) {
                throw new IllegalArgumentException("Version string cannot be null");
            }
            String cleaned = versionStr.trim();
            if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
                cleaned = cleaned.substring(1);
            }
            Matcher matcher = SEMVER_PATTERN.matcher(cleaned);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid SemVer 2.0.0 string: " + versionStr);
            }
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            String pre = matcher.group(4);
            return new SemVer(major, minor, patch, pre);
        }

        @Override
        public int compareTo(SemVer other) {
            if (this.major != other.major) return Integer.compare(this.major, other.major);
            if (this.minor != other.minor) return Integer.compare(this.minor, other.minor);
            if (this.patch != other.patch) return Integer.compare(this.patch, other.patch);

            if (this.prerelease == null && other.prerelease != null) return 1;
            if (this.prerelease != null && other.prerelease == null) return -1;
            if (this.prerelease != null && other.prerelease != null) {
                return this.prerelease.compareTo(other.prerelease);
            }
            return 0;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (prerelease != null ? "-" + prerelease : "");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SemVer)) return false;
            SemVer semVer = (SemVer) o;
            return major == semVer.major && minor == semVer.minor && patch == semVer.patch && Objects.equals(prerelease, semVer.prerelease);
        }

        @Override
        public int hashCode() {
            return Objects.hash(major, minor, patch, prerelease);
        }
    }

    public static final class CompatibilityResult {
        public final CompatibilityStatus status;
        public final String clientVersion;
        public final String targetVersion;
        public final String minSupportedVersion;
        public final boolean isUpgradeRequired;
        public final String messageEn;
        public final String messageBn;
        public final String downloadUrl;

        public CompatibilityResult(
            CompatibilityStatus status,
            String clientVersion,
            String targetVersion,
            String minSupportedVersion,
            boolean isUpgradeRequired,
            String messageEn,
            String messageBn,
            String downloadUrl
        ) {
            this.status = status;
            this.clientVersion = clientVersion;
            this.targetVersion = targetVersion;
            this.minSupportedVersion = minSupportedVersion;
            this.isUpgradeRequired = isUpgradeRequired;
            this.messageEn = messageEn;
            this.messageBn = messageBn;
            this.downloadUrl = downloadUrl;
        }
    }

    public static CompatibilityResult evaluateLocal(String clientVersion, String minSupported, String targetVersion) {
        SemVer c = SemVer.parse(clientVersion);
        SemVer min = SemVer.parse(minSupported);
        SemVer target = SemVer.parse(targetVersion);

        CompatibilityStatus status;
        boolean upgradeRequired = false;

        if (c.compareTo(min) < 0) {
            status = CompatibilityStatus.UPGRADE_REQUIRED;
            upgradeRequired = true;
        } else if (c.compareTo(target) < 0) {
            status = CompatibilityStatus.DEPRECATED;
        } else {
            status = CompatibilityStatus.SUPPORTED;
        }

        String msgEn = (status == CompatibilityStatus.UPGRADE_REQUIRED)
            ? "Desktop POS v" + clientVersion + " is unsupported. Upgrade to v" + targetVersion + " required."
            : (status == CompatibilityStatus.DEPRECATED)
                ? "Desktop POS v" + clientVersion + " is deprecated. Upgrade to v" + targetVersion + " recommended."
                : "Desktop POS v" + clientVersion + " is fully supported.";

        String msgBn = (status == CompatibilityStatus.UPGRADE_REQUIRED)
            ? "ডেস্কটপ পিওএস v" + clientVersion + " আর সমর্থিত নয়। v" + targetVersion + "-এ আপগ্রেড করা আবশ্যক।"
            : (status == CompatibilityStatus.DEPRECATED)
                ? "ডেস্কটপ পিওএস v" + clientVersion + " অপ্রচলিত। v" + targetVersion + "-এ আপগ্রেড বাঞ্ছনীয়।"
                : "ডেস্কটপ পিওএস v" + clientVersion + " সম্পূর্ণ সমর্থিত।";

        return new CompatibilityResult(
            status,
            clientVersion,
            targetVersion,
            minSupported,
            upgradeRequired,
            msgEn,
            msgBn,
            "https://pharmacy.bd/desktop"
        );
    }

    public static String formatBanner(CompatibilityResult result, String locale) {
        if ("bn".equalsIgnoreCase(locale)) {
            return result.messageBn;
        }
        return result.messageEn;
    }
}
