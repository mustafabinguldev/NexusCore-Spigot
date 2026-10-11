package io.github.mustafabinguldev.nexusspigot.channel;

/**
 * Base class of messages sent over a {@link NexusChannel}. Subclasses need a
 * {@link MessageTag}, a no-arg constructor and {@link MessageField} on every
 * field that should be transmitted.
 */
public abstract class ChannelMessage {

    /** Filled with a random id when left empty. */
    public String id;

    /** Name of the sending server, filled automatically. */
    public String source;
}