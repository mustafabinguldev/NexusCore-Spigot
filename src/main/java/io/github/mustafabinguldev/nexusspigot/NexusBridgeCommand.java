package io.github.mustafabinguldev.nexusspigot;

import io.github.mustafabinguldev.nexusspigot.api.NexusAddon;
import io.github.mustafabinguldev.nexusspigot.internal.BridgeCore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

final class NexusBridgeCommand implements TabExecutor {

    private final BridgeCore core;

    NexusBridgeCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [status]");
            return true;
        }

        Collection<NexusAddon<?>> addons = core.getAddons();
        String addonList = addons.isEmpty() ? "-" : addons.stream()
                .map(addon -> addon.name() + "#" + addon.protocolId())
                .collect(Collectors.joining(", "));
        Collection<String> channels = core.channelNames();

        sender.sendMessage(ChatColor.GOLD + "NexusCore-Spigot " + ChatColor.GRAY + "(" + core.serverName() + ")");
        sender.sendMessage(ChatColor.GRAY + "Connection: " + (core.isConnected()
                ? ChatColor.GREEN + "connected"
                : ChatColor.RED + "disconnected"));
        sender.sendMessage(ChatColor.GRAY + "Last heartbeat: " + ChatColor.WHITE + core.millisSinceHeartbeat() + " ms ago");
        sender.sendMessage(ChatColor.GRAY + "Signing: " + ChatColor.WHITE
                + (core.settings().security().signingEnabled() ? "enabled" : "disabled"));
        sender.sendMessage(ChatColor.GRAY + "Queued writes: " + ChatColor.WHITE + core.queuedPackets());
        sender.sendMessage(ChatColor.GRAY + "Addons: " + ChatColor.WHITE + addonList);
        sender.sendMessage(ChatColor.GRAY + "Channels: " + ChatColor.WHITE + (channels.isEmpty() ? "-" : String.join(", ", channels)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("status") : List.of();
    }
}