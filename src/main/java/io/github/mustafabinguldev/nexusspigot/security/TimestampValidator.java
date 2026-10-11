package io.github.mustafabinguldev.nexusspigot.security;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

public class TimestampValidator implements MessageValidator {

    private static final String FIELD_TIMESTAMP = "timestamp";

    private final SecuritySettings settings;

    public TimestampValidator(SecuritySettings settings) {
        this.settings = settings;
    }

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        if (!message.containsKey(FIELD_TIMESTAMP)) {
            return ValidationResult.reject("timestamp field missing");
        }
        try {
            long difference = Math.abs(System.currentTimeMillis() - message.get(FIELD_TIMESTAMP, Long.class));
            if (difference > settings.timestampWindowMs()) {
                return ValidationResult.reject("timestamp outside the accepted window (difference: " + difference + " ms)");
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.reject("timestamp could not be read: " + e.getMessage());
        }
    }
}
