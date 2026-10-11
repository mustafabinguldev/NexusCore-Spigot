package io.github.mustafabinguldev.nexusspigot.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread when NexusCore heartbeats arrive again after a
 * disconnect. Queued writes are flushed right after this event.
 */
public class NexusConnectionRestoredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long downtimeMillis;

    public NexusConnectionRestoredEvent(long downtimeMillis) {
        this.downtimeMillis = downtimeMillis;
    }

    public long getDowntimeMillis() {
        return downtimeMillis;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
