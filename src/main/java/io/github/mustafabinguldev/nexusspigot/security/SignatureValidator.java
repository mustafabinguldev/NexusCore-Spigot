package io.github.mustafabinguldev.nexusspigot.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.logging.Logger;

public class SignatureValidator implements MessageValidator {

    private static final String FIELD_SIG = "sig";

    private final SecuritySettings settings;
    private final Logger logger;
    private volatile boolean warnedOnce = false;

    public SignatureValidator(SecuritySettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!settings.signingEnabled()) {
            if (!settings.allowUnsigned()) {
                return ValidationResult.reject("no signing key configured and unsigned messages are disabled");
            }
            if (!warnedOnce) {
                warnedOnce = true;
                logger.warning("No signing key configured - signature verification is DISABLED. "
                        + "Set security.signing-key (or NEXUS_SIGNING_KEY) in production.");
            }
            return ValidationResult.ok();
        }

        String providedSig = message.getString(FIELD_SIG);
        if (providedSig == null) {
            return ValidationResult.reject("signature field missing");
        }

        try {
            NexusJsonDataContainer withoutSig = new NexusJsonDataContainer(message.toFullJson());
            withoutSig.remove(FIELD_SIG);

            String expectedSig = HmacSigner.sign(withoutSig.toFullJson(), settings.signingKey());
            if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                    providedSig.getBytes(StandardCharsets.UTF_8))) {
                return ValidationResult.reject("signature does not match (wrong or outdated key?)");
            }
            return ValidationResult.ok();
        } catch (JsonProcessingException e) {
            return ValidationResult.reject("JSON error while verifying signature: " + e.getMessage());
        }
    }
}
