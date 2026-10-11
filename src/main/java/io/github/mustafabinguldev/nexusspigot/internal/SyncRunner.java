package io.github.mustafabinguldev.nexusspigot.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Moves work onto the main server thread. */
final class SyncRunner {

    private final Plugin plugin;

    SyncRunner(Plugin plugin) {
        this.plugin = plugin;
    }

    void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}