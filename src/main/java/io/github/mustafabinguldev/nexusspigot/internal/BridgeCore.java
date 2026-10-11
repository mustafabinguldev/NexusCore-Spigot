package io.github.mustafabinguldev.nexusspigot.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.mustafabinguldev.nexusspigot.api.NexusAddon;
import io.github.mustafabinguldev.nexusspigot.api.NexusBridge;
import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;
import io.github.mustafabinguldev.nexusspigot.channel.NexusChannel;
import io.github.mustafabinguldev.nexusspigot.redis.RedisConnection;
import io.github.mustafabinguldev.nexusspigot.security.MessageAuth;
import io.github.mustafabinguldev.nexusspigot.security.MessageValidationChain;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires Redis, security, the watchdog, addons and channels together.
 *
 * <p>Requests to NexusCore go onto its Redis Stream (XADD), so they survive a
 * NexusCore restart. Answers arrive on {@code <prefix><server-name>}, the
 * heartbeat on the live channel, both over Pub/Sub.</p>
 */
public final class BridgeCore implements NexusBridge, AddonContext {

    private final BridgeSettings settings;
    private final Logger logger;
    private final SyncRunner sync;
    private final RedisConnection redis;
    private final MessageAuth auth;
    private final MessageValidationChain validation;
    private final NexusWatchdog watchdog;
    private final ScheduledExecutorService scheduler;

    private final Map<Integer, NexusAddon<?>> addons = new ConcurrentHashMap<>();
    private final Map<String, NexusChannel> channels = new ConcurrentHashMap<>();

    private final Queue<NexusJsonDataContainer> offlineQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger offlineQueueSize = new AtomicInteger();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile long lastQueueFullWarning;

    public BridgeCore(Plugin plugin, BridgeSettings settings) {
        this.settings = settings;
        this.logger = plugin.getLogger();
        this.sync = new SyncRunner(plugin);
        this.auth = new MessageAuth(settings.security());
        this.validation = MessageValidationChain.standard(settings.security(), logger);
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "NexusSpigot-Scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.redis = new RedisConnection(settings.redis(), logger, this::onRedisMessage);
        this.watchdog = new NexusWatchdog(settings.watchdog(), sync, logger, () -> scheduler.execute(this::flushQueue));

        plugin.getServer().getPluginManager().registerEvents(watchdog, plugin);
    }

    public void start() {
        try {
            redis.ping();
            logger.info("Connected to Redis at " + settings.redis().host() + ":" + settings.redis().port() + ".");
        } catch (Exception e) {
            logger.severe("Redis is not reachable (" + e.getMessage() + "). The bridge keeps retrying in the background.");
        }
        redis.subscribe(settings.responseChannel());
        redis.subscribe(settings.liveChannel());
        redis.start();
        watchdog.start(scheduler);
    }

    public void stop() {
        watchdog.stop();
        for (NexusAddon<?> addon : addons.values()) {
            addon.detach();
        }
        addons.clear();
        if (offlineQueueSize.get() > 0) {
            logger.warning(offlineQueueSize.get() + " queued write(s) were not sent because the server is stopping.");
        }
        scheduler.shutdownNow();
        redis.stop();
        validation.close();
    }

    /* ---------------------------------------------------------------------
       Inbound
       --------------------------------------------------------------------- */

    private void onRedisMessage(String channel, String message) {
        NexusJsonDataContainer packet;
        try {
            packet = new NexusJsonDataContainer(message);
        } catch (JsonProcessingException e) {
            debug("Ignoring non-JSON message on " + channel);
            return;
        }

        if (channel.equals(settings.liveChannel())) {
            if (validation.runAll(packet)) watchdog.onHeartbeat(packet);
            return;
        }

        if (channel.equals(settings.responseChannel())) {
            if (validation.runAll(packet)) dispatch(() -> handleCoreResponse(packet));
            return;
        }

        NexusChannel target = channels.get(channel);
        if (target != null && validation.runAll(packet)) {
            dispatch(() -> target.dispatch(packet));
        }
    }

    private void handleCoreResponse(NexusJsonDataContainer packet) {
        String type = packet.getString("type");
        Integer protocol = packet.get("protocol", Integer.class);
        if (type == null || protocol == null) return;

        NexusAddon<?> addon = addons.get(protocol);
        if (addon == null) {
            debug("No addon registered for protocol " + protocol + " (" + type + ")");
            return;
        }

        try {
            switch (type) {
                case "BROADCAST" -> {
                    NexusJsonDataContainer data = toContainer(packet.asMap().get("data"));
                    if (data != null) addon.handleData(data);
                }
                case "RANKING_RESPONSE" -> addon.handleRanking(packet.asMap().get("response"));
                case "RANK_FINDER_RESPONSE" -> addon.handleRankPosition(packet.getString("key"),
                        packet.get("position", Integer.class));
                default -> debug("Unhandled response type " + type + " for protocol " + protocol);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while handling " + type + " for addon " + addon.name(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static NexusJsonDataContainer toContainer(Object data) throws JsonProcessingException {
        if (data instanceof Map<?, ?> map) return new NexusJsonDataContainer((Map<String, ?>) map);
        if (data instanceof String json) return new NexusJsonDataContainer(json);
        return null;
    }

    private void dispatch(Runnable task) {
        if (settings.mainThreadCallbacks()) {
            sync.run(task);
        } else {
            task.run();
        }
    }

    /* ---------------------------------------------------------------------
       Outbound
       --------------------------------------------------------------------- */

    @Override
    public boolean sendToCore(NexusJsonDataContainer packet, boolean queueIfOffline) {
        // Keep write order: while older writes wait in the queue, new ones line up behind them.
        if (queueIfOffline && (!watchdog.isConnected() || offlineQueueSize.get() > 0)) {
            boolean queued = enqueue(packet);
            if (watchdog.isConnected()) scheduler.execute(this::flushQueue);
            return queued;
        }
        if (writeToStream(packet)) return true;
        return queueIfOffline && enqueue(packet);
    }

    private boolean writeToStream(NexusJsonDataContainer packet) {
        String stamped = auth.stamp(packet.toFullJson());
        debug("-> " + stamped);
        return redis.addToStream(settings.streamKey(), settings.streamPayloadField(), stamped);
    }

    private boolean enqueue(NexusJsonDataContainer packet) {
        if (offlineQueueSize.get() >= settings.offlineQueueSize()) {
            long now = System.currentTimeMillis();
            if (now - lastQueueFullWarning > 10_000L) {
                lastQueueFullWarning = now;
                logger.warning("Offline queue is full (" + settings.offlineQueueSize() + "), dropping writes.");
            }
            return false;
        }
        offlineQueue.add(packet);
        offlineQueueSize.incrementAndGet();
        return true;
    }

    private void flushQueue() {
        if (!flushing.compareAndSet(false, true)) return;
        int sent = 0;
        try {
            NexusJsonDataContainer packet;
            while (watchdog.isConnected() && (packet = offlineQueue.peek()) != null) {
                if (!writeToStream(packet)) break;
                offlineQueue.poll();
                offlineQueueSize.decrementAndGet();
                sent++;
            }
        } finally {
            flushing.set(false);
        }
        if (sent > 0) logger.info("Sent " + sent + " queued write(s) to NexusCore.");
    }

    /* ---------------------------------------------------------------------
       NexusBridge
       --------------------------------------------------------------------- */

    @Override
    public String serverName() {
        return settings.serverName();
    }

    @Override
    public boolean isConnected() {
        return watchdog.isConnected();
    }

    @Override
    public void registerAddon(NexusAddon<?> addon) {
        addon.attach(this);
        NexusAddon<?> previous = addons.putIfAbsent(addon.protocolId(), addon);
        if (previous != null) {
            addon.detach();
            throw new IllegalStateException("Protocol id " + addon.protocolId() + " is already used by " + previous.name());
        }
        logger.info("Registered addon " + addon.name() + " (protocol " + addon.protocolId() + ").");
    }

    @Override
    public void unregisterAddon(int protocolId) {
        NexusAddon<?> addon = addons.remove(protocolId);
        if (addon != null) addon.detach();
    }

    @Override
    public Optional<NexusAddon<?>> getAddon(int protocolId) {
        return Optional.ofNullable(addons.get(protocolId));
    }

    @Override
    public Collection<NexusAddon<?>> getAddons() {
        return List.copyOf(addons.values());
    }

    @Override
    public NexusChannel channel(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Channel name must not be empty");
        if (name.equals(settings.liveChannel()) || name.startsWith(settings.responseChannelPrefix())) {
            throw new IllegalArgumentException("Channel name " + name + " is reserved for NexusCore");
        }
        return channels.computeIfAbsent(name, key -> {
            NexusChannel channel = new NexusChannel(key, settings.serverName(),
                    json -> redis.publish(key, auth.stamp(json)), logger);
            redis.subscribe(key);
            return channel;
        });
    }

    @Override
    public int queuedPackets() {
        return offlineQueueSize.get();
    }

    public Collection<String> channelNames() {
        return List.copyOf(channels.keySet());
    }

    public long millisSinceHeartbeat() {
        return watchdog.millisSinceHeartbeat();
    }

    public BridgeSettings settings() {
        return settings;
    }

    /* ---------------------------------------------------------------------
       AddonContext
       --------------------------------------------------------------------- */

    @Override
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public long requestTimeoutMs() {
        return settings.requestTimeoutMs();
    }

    @Override
    public long retryIntervalMs() {
        return settings.retryIntervalMs();
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public boolean debug() {
        return settings.debug();
    }

    private void debug(String message) {
        if (settings.debug()) logger.info("[debug] " + message);
    }
}