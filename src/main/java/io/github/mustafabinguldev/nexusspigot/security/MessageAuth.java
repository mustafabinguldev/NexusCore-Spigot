package io.github.mustafabinguldev.nexusspigot.security;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.mustafabinguldev.nexusspigot.util.NexusJson;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stamps outgoing packets the same way NexusCore does: every packet gets a
 * timestamp and nonce (NexusCore rejects packets without them), and an HMAC
 * signature over the key-sorted JSON when a signing key is configured.
 */
public final class MessageAuth {

    private final SecuritySettings settings;

    public MessageAuth(SecuritySettings settings) {
        this.settings = settings;
    }

    public String stamp(String json) {
        try {
            Map<String, Object> canonical = new TreeMap<>(
                    NexusJson.mapper().readValue(json, new TypeReference<Map<String, Object>>() {})
            );
            canonical.put("timestamp", System.currentTimeMillis());
            canonical.put("nonce", UUID.randomUUID().toString());
            canonical.remove("sig");

            if (settings.signingEnabled()) {
                String unsigned = NexusJson.mapper().writeValueAsString(canonical);
                canonical.put("sig", HmacSigner.sign(unsigned, settings.signingKey()));
            }
            return NexusJson.mapper().writeValueAsString(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("Outgoing message could not be stamped", e);
        }
    }
}
