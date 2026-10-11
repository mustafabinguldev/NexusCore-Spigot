package io.github.mustafabinguldev.nexusspigot.api;

/**
 * Static access to the running bridge.
 */
public final class NexusSpigot {

    private static volatile NexusBridge bridge;

    public static NexusBridge bridge() {
        NexusBridge current = bridge;
        if (current == null) {
            throw new IllegalStateException("NexusCore-Spigot is not enabled. Add 'depend: [NexusCore-Spigot]' to your plugin.yml.");
        }
        return current;
    }

    public static boolean isAvailable() {
        return bridge != null;
    }

    /** Set by the plugin itself. */
    public static void setBridge(NexusBridge instance) {
        bridge = instance;
    }

    private NexusSpigot() {
    }
}
