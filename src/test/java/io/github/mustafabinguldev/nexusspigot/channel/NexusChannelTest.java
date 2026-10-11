package io.github.mustafabinguldev.nexusspigot.channel;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusChannelTest {

    @MessageTag("transfer")
    static class TransferMessage extends ChannelMessage {
        @MessageField("player")
        String player;
        @MessageField("targets")
        List<String> targets;
        @MessageField("meta")
        Map<String, Integer> meta;
    }

    static class Untagged extends ChannelMessage {
    }

    @Test
    void publishedMessageIsDeliveredWithTypes() throws Exception {
        List<String> published = new ArrayList<>();
        NexusChannel channel = new NexusChannel("network", "lobby-1", json -> published.add(json), Logger.getLogger("test"));

        List<TransferMessage> received = new ArrayList<>();
        channel.register(TransferMessage.class, received::add);

        TransferMessage message = new TransferMessage();
        message.player = "Steve";
        message.targets = List.of("game-1", "game-2");
        message.meta = Map.of("slot", 3);
        assertTrue(channel.publish(message));

        NexusJsonDataContainer wire = new NexusJsonDataContainer(published.get(0));
        assertEquals("transfer", wire.getString("tag"));
        assertEquals("lobby-1", wire.getString("source"));
        assertNotNull(wire.getString("id"));

        channel.dispatch(wire);
        TransferMessage copy = received.get(0);
        assertEquals("Steve", copy.player);
        assertEquals(List.of("game-1", "game-2"), copy.targets);
        assertEquals(Map.of("slot", 3), copy.meta);
        assertEquals("lobby-1", copy.source);
        assertEquals(message.id, copy.id);
    }

    @Test
    void ownMessagesCanBeIgnored() throws Exception {
        List<String> published = new ArrayList<>();
        NexusChannel channel = new NexusChannel("network", "lobby-1", json -> published.add(json), Logger.getLogger("test"))
                .receiveOwnMessages(false);
        List<TransferMessage> received = new ArrayList<>();
        channel.register(TransferMessage.class, received::add);

        channel.publish(new TransferMessage());
        channel.dispatch(new NexusJsonDataContainer(published.get(0)));
        assertTrue(received.isEmpty());
    }

    @Test
    void untaggedMessageIsRejected() {
        NexusChannel channel = new NexusChannel("network", "lobby-1", json -> true, Logger.getLogger("test"));
        assertThrows(IllegalArgumentException.class, () -> channel.register(Untagged.class, m -> { }));
    }
}