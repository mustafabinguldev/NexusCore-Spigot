package io.github.mustafabinguldev.nexusspigot.internal;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/** What an addon needs from the bridge. Not part of the public API. */
public interface AddonContext {

    String serverName();

    boolean sendToCore(NexusJsonDataContainer packet, boolean queueIfOffline);

    ScheduledExecutorService scheduler();

    long requestTimeoutMs();

    long retryIntervalMs();

    Logger logger();

    boolean debug();
}