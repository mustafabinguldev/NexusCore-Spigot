package io.github.mustafabinguldev.nexusspigot.security;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Rejects replayed packets by remembering nonces for the timestamp window. */
public class NonceValidator implements MessageValidator, AutoCloseable {

    private static final String FIELD_NONCE = "nonce";

    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();
    private final SecuritySettings settings;
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "NexusSpigot-Nonce-Cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public NonceValidator(SecuritySettings settings) {
        this.settings = settings;
        long window = settings.timestampWindowMs();
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired, window, window, TimeUnit.MILLISECONDS);
    }

    private void cleanupExpired() {
        long expiration = System.currentTimeMillis() - settings.timestampWindowMs();
        usedNonces.entrySet().removeIf(entry -> entry.getValue() < expiration);
    }

    @Override
    public ValidationResult validate(NexusJsonDataContainer message) {
        String nonce = message.getString(FIELD_NONCE);
        if (nonce == null || nonce.isBlank()) {
            return ValidationResult.reject("nonce missing");
        }
        if (usedNonces.putIfAbsent(nonce, System.currentTimeMillis()) != null) {
            return ValidationResult.reject("nonce reused: " + nonce);
        }
        return ValidationResult.ok();
    }

    @Override
    public void close() {
        cleanupScheduler.shutdownNow();
    }
}
