package io.github.mustafabinguldev.nexusspigot.api;

import io.github.mustafabinguldev.nexusspigot.channel.NexusChannel;

import java.util.Collection;
import java.util.Optional;

/**
 * Entry point of the bridge. Obtain it with {@link NexusSpigot#bridge()} or
 * through the Bukkit {@code ServicesManager}.
 */
public interface NexusBridge {

    /** Name of this server, used as the response target by NexusCore. */
    String serverName();

    /** False while the watchdog has not seen a NexusCore heartbeat in time. */
    boolean isConnected();

    /** Registers an addon whose protocol id matches an addon on the NexusCore side. */
    void registerAddon(NexusAddon<?> addon);

    void unregisterAddon(int protocolId);

    Optional<NexusAddon<?>> getAddon(int protocolId);

    Collection<NexusAddon<?>> getAddons();

    /** Server-to-server Pub/Sub channel. Subscribes on first use. */
    NexusChannel channel(String name);

    /**
     * Sends a raw packet to NexusCore (stamped and signed automatically).
     *
     * @param queueIfOffline keep the packet and send it once the connection is back
     * @return false if the packet could not be sent or queued
     */
    boolean sendToCore(NexusJsonDataContainer packet, boolean queueIfOffline);

    /** Number of packets waiting for the connection to come back. */
    int queuedPackets();
}
