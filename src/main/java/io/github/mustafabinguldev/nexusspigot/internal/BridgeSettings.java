package io.github.mustafabinguldev.nexusspigot.internal;

import io.github.mustafabinguldev.nexusspigot.redis.RedisSettings;
import io.github.mustafabinguldev.nexusspigot.security.SecuritySettings;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public record BridgeSettings(
        String serverName,
        RedisSettings redis,
        String streamKey,
        String streamPayloadField,
        String liveChannel,
        String responseChannelPrefix,
        SecuritySettings security,
        WatchdogSettings watchdog,
        long requestTimeoutMs,
        long retryIntervalMs,
        int offlineQueueSize,
        boolean mainThreadCallbacks,
        boolean debug
) {

    public static final String SIGNING_KEY_ENV = "NEXUS_SIGNING_KEY";
    public static final String ALLOW_UNSIGNED_ENV = "NEXUS_ALLOW_UNSIGNED_MESSAGES";

    public String responseChannel() {
        return responseChannelPrefix + serverName;
    }

    public static BridgeSettings from(FileConfiguration config) {
        String serverName = config.getString("server-name", "").trim();
        if (serverName.isEmpty()) {
            throw new IllegalArgumentException("server-name must not be empty");
        }

        RedisSettings redis = new RedisSettings(
                config.getString("redis.host", "localhost"),
                config.getInt("redis.port", 6379),
                config.getString("redis.username", ""),
                config.getString("redis.password", ""),
                config.getInt("redis.database", 0),
                config.getInt("redis.timeout-ms", 2000),
                config.getBoolean("redis.ssl", false)
        );

        String signingKey = config.getString("security.signing-key", "");
        if (signingKey == null || signingKey.isBlank()) {
            signingKey = System.getenv().getOrDefault(SIGNING_KEY_ENV, "");
        }
        boolean allowUnsigned = config.getBoolean("security.allow-unsigned-messages", false)
                || Boolean.parseBoolean(System.getenv().getOrDefault(ALLOW_UNSIGNED_ENV, "false"));
        SecuritySettings security = new SecuritySettings(
                signingKey,
                allowUnsigned,
                Math.max(1000L, config.getLong("security.timestamp-window-ms", 300_000L))
        );

        WatchdogSettings watchdog = new WatchdogSettings(
                config.getBoolean("watchdog.enabled", true),
                Math.max(1000L, config.getLong("watchdog.timeout-ms", 5000L)),
                config.getBoolean("watchdog.kick-players", true),
                config.getBoolean("watchdog.block-logins", true),
                color(config.getString("watchdog.kick-message", "")),
                color(config.getString("watchdog.login-message", ""))
        );

        return new BridgeSettings(
                serverName,
                redis,
                config.getString("nexus.stream-key", "darkland_nexus_stream"),
                config.getString("nexus.stream-payload-field", "payload"),
                config.getString("nexus.live-channel", "darkland_nexus_live"),
                config.getString("nexus.response-channel-prefix", "darkland_nexus_"),
                security,
                watchdog,
                Math.max(100L, config.getLong("requests.timeout-ms", 5000L)),
                config.getLong("requests.retry-interval-ms", 1000L),
                Math.max(0, config.getInt("requests.offline-queue-size", 10_000)),
                config.getBoolean("main-thread-callbacks", true),
                config.getBoolean("debug", false)
        );
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}