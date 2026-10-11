package io.github.mustafabinguldev.nexusspigot.internal;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;
import io.github.mustafabinguldev.nexusspigot.event.NexusConnectionLostEvent;
import io.github.mustafabinguldev.nexusspigot.event.NexusConnectionRestoredEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Tracks the LIVE heartbeat NexusCore publishes every second. Without it the
 * bridge is considered disconnected: writes are queued, logins can be blocked
 * and players can be kicked so nobody plays on stale data.
 */
final class NexusWatchdog implements Listener {

    private final WatchdogSettings settings;
    private final SyncRunner sync;
    private final Logger logger;
    private final Runnable onRestored;

    private final AtomicBoolean connected = new AtomicBoolean(true);
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private volatile long lostAt;
    private ScheduledFuture<?> task;

    NexusWatchdog(WatchdogSettings settings, SyncRunner sync, Logger logger, Runnable onRestored) {
        this.settings = settings;
        this.sync = sync;
        this.logger = logger;
        this.onRestored = onRestored;
    }

    void start(ScheduledExecutorService scheduler) {
        if (!settings.enabled()) return;
        lastHeartbeat = System.currentTimeMillis();
        task = scheduler.scheduleAtFixedRate(this::check, 1, 1, TimeUnit.SECONDS);
    }

    void stop() {
        if (task != null) task.cancel(false);
    }

    boolean isConnected() {
        return !settings.enabled() || connected.get();
    }

    long millisSinceHeartbeat() {
        return System.currentTimeMillis() - lastHeartbeat;
    }

    /** Called with an already verified packet from the live channel. */
    void onHeartbeat(NexusJsonDataContainer packet) {
        if (!"LIVE".equals(packet.getString("type"))) return;
        lastHeartbeat = System.currentTimeMillis();

        if (settings.enabled() && connected.compareAndSet(false, true)) {
            long downtime = System.currentTimeMillis() - lostAt;
            logger.info("Connection to NexusCore restored after " + downtime + " ms.");
            sync.run(() -> {
                Bukkit.getPluginManager().callEvent(new NexusConnectionRestoredEvent(downtime));
                onRestored.run();
            });
        }
    }

    private void check() {
        long silence = millisSinceHeartbeat();
        if (silence <= settings.timeoutMs() || !connected.compareAndSet(true, false)) return;

        lostAt = System.currentTimeMillis();
        logger.severe("No heartbeat from NexusCore for " + silence + " ms. Writes are queued until it is back.");
        sync.run(() -> {
            NexusConnectionLostEvent event = new NexusConnectionLostEvent(silence, settings.kickPlayers());
            Bukkit.getPluginManager().callEvent(event);
            if (event.isKickPlayers() && !connected.get()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.kickPlayer(settings.kickMessage());
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (settings.blockLogins() && !isConnected()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, settings.loginMessage());
        }
    }
}