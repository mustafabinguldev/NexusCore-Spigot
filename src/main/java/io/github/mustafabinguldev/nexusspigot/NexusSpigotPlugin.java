package io.github.mustafabinguldev.nexusspigot;

import io.github.mustafabinguldev.nexusspigot.api.NexusBridge;
import io.github.mustafabinguldev.nexusspigot.api.NexusSpigot;
import io.github.mustafabinguldev.nexusspigot.internal.BridgeCore;
import io.github.mustafabinguldev.nexusspigot.internal.BridgeSettings;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class NexusSpigotPlugin extends JavaPlugin {

    private BridgeCore core;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        BridgeSettings settings;
        try {
            settings = BridgeSettings.from(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config.yml: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if ("server-1".equals(settings.serverName())) {
            getLogger().warning("server-name is still the default 'server-1'. Give every server a unique name.");
        }
        if (!settings.security().signingEnabled()) {
            getLogger().warning("No signing key set (security.signing-key / NEXUS_SIGNING_KEY). "
                    + (settings.security().allowUnsigned()
                    ? "Unsigned messages are accepted - do not run like this in production."
                    : "NexusCore messages will be rejected until a key is set or allow-unsigned-messages is enabled."));
        }

        core = new BridgeCore(this, settings);
        core.start();

        NexusSpigot.setBridge(core);
        getServer().getServicesManager().register(NexusBridge.class, core, this, ServicePriority.Normal);

        PluginCommand command = getCommand("nexusbridge");
        if (command != null) {
            NexusBridgeCommand executor = new NexusBridgeCommand(core);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("NexusCore bridge started as '" + settings.serverName() + "'.");
    }

    @Override
    public void onDisable() {
        NexusSpigot.setBridge(null);
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            core.stop();
            core = null;
        }
    }
}