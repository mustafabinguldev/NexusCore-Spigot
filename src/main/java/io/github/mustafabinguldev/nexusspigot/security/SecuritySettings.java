package io.github.mustafabinguldev.nexusspigot.security;

/**
 * @param signingKey        shared HMAC key; blank disables signing
 * @param allowUnsigned     accept unsigned inbound messages when no key is set
 * @param timestampWindowMs accepted clock difference for inbound messages
 */
public record SecuritySettings(String signingKey, boolean allowUnsigned, long timestampWindowMs) {

    public SecuritySettings {
        signingKey = signingKey == null ? "" : signingKey;
    }

    public boolean signingEnabled() {
        return !signingKey.isBlank();
    }
}
