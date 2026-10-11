package io.github.mustafabinguldev.nexusspigot.channel;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-to-server Pub/Sub channel. Messages are stamped and signed like
 * NexusCore packets and verified on arrival.
 *
 * <pre>{@code
 * NexusChannel channel = NexusSpigot.bridge().channel("my_network");
 * channel.register(ChatMessage.class, msg -> Bukkit.broadcastMessage(msg.text));
 * channel.publish(new ChatMessage("hello"));
 * }</pre>
 */
public final class NexusChannel {

    private final String name;
    private final String serverName;
    private final Predicate<String> publisher;
    private final Logger logger;
    private final Map<String, Registration<?>> registrations = new ConcurrentHashMap<>();
    private final Map<Class<?>, MessageCodec<?>> codecs = new ConcurrentHashMap<>();

    private volatile boolean receiveOwnMessages = true;

    /** Created by the bridge, use {@code NexusBridge#channel(String)}. */
    public NexusChannel(String name, String serverName, Predicate<String> publisher, Logger logger) {
        this.name = name;
        this.serverName = serverName;
        this.publisher = publisher;
        this.logger = logger;
    }

    public String name() {
        return name;
    }

    /** Whether messages published by this server are delivered back to it. Default true. */
    public NexusChannel receiveOwnMessages(boolean receive) {
        this.receiveOwnMessages = receive;
        return this;
    }

    /** Handles incoming messages of {@code type}. Replaces an earlier handler for the same tag. */
    public <T extends ChannelMessage> NexusChannel register(Class<T> type, Consumer<T> handler) {
        MessageCodec<T> codec = codec(type);
        registrations.put(codec.tag(), new Registration<>(codec, handler));
        return this;
    }

    public NexusChannel unregister(Class<? extends ChannelMessage> type) {
        MessageTag tag = type.getAnnotation(MessageTag.class);
        if (tag != null) registrations.remove(tag.value());
        return this;
    }

    /** Publishes to every server subscribed to this channel. */
    @SuppressWarnings("unchecked")
    public <T extends ChannelMessage> boolean publish(T message) {
        if (message.id == null || message.id.isBlank()) message.id = UUID.randomUUID().toString();
        message.source = serverName;
        MessageCodec<T> codec = codec((Class<T>) message.getClass());
        return publisher.test(new NexusJsonDataContainer(codec.encode(message)).toFullJson());
    }

    /** Called by the bridge with an already verified message. */
    public void dispatch(NexusJsonDataContainer json) {
        String tag = json.getString(MessageCodec.KEY_TAG);
        if (tag == null) return;
        if (!receiveOwnMessages && serverName.equals(json.getString(MessageCodec.KEY_SOURCE))) return;

        Registration<?> registration = registrations.get(tag);
        if (registration == null) return;
        try {
            registration.deliver(json.asMap());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while handling message '" + tag + "' on channel " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends ChannelMessage> MessageCodec<T> codec(Class<T> type) {
        return (MessageCodec<T>) codecs.computeIfAbsent(type, t -> new MessageCodec<>(type));
    }

    private record Registration<T extends ChannelMessage>(MessageCodec<T> codec, Consumer<T> handler) {
        void deliver(Map<String, Object> json) {
            handler.accept(codec.decode(json));
        }
    }
}