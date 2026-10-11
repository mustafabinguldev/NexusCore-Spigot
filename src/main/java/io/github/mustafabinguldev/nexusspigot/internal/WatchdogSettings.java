package io.github.mustafabinguldev.nexusspigot.internal;

public record WatchdogSettings(boolean enabled, long timeoutMs, boolean kickPlayers, boolean blockLogins,
                               String kickMessage, String loginMessage) {
}