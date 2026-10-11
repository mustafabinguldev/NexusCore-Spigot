package io.github.mustafabinguldev.nexusspigot.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread when no NexusCore heartbeat arrived within the
 * watchdog timeout. Writes are queued until {@link NexusConnectionRestoredEvent}.
 */
public class NexusConnectionLostEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long millisSinceLastHeartbeat;
    private boolean kickPlayers;

    public NexusConnectionLostEvent(long millisSinceLastHeartbeat, boolean kickPlayers) {
        this.millisSinceLastHeartbeat = millisSinceLastHeartbeat;
        this.kickPlayers = kickPlayers;
    }

    public long getMillisSinceLastHeartbeat() {
        return millisSinceLastHeartbeat;
    }

    public boolean isKickPlayers() {
        return kickPlayers;
    }

    /** Overrides the watchdog.kick-players setting for this disconnect. */
    public void setKickPlayers(boolean kickPlayers) {
        this.kickPlayers = kickPlayers;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
