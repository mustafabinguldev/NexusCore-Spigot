package io.github.mustafabinguldev.nexusspigot.security;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

import java.util.List;
import java.util.logging.Logger;

public final class MessageValidationChain {

    private final List<MessageValidator> validators;
    private final Logger logger;

    public MessageValidationChain(List<MessageValidator> validators, Logger logger) {
        this.validators = List.copyOf(validators);
        this.logger = logger;
    }

    /** Signature, timestamp and nonce checks, in that order. */
    public static MessageValidationChain standard(SecuritySettings settings, Logger logger) {
        return new MessageValidationChain(List.of(
                new SignatureValidator(settings, logger),
                new TimestampValidator(settings),
                new NonceValidator(settings)
        ), logger);
    }

    public boolean runAll(NexusJsonDataContainer message) {
        for (MessageValidator validator : validators) {
            ValidationResult result = validator.validate(message);
            if (!result.valid()) {
                logger.warning("[" + validator.getClass().getSimpleName() + "] message rejected: " + result.reason());
                return false;
            }
        }
        return true;
    }

    public void close() {
        for (MessageValidator validator : validators) {
            if (validator instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
