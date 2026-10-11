package io.github.mustafabinguldev.nexusspigot.security;

import io.github.mustafabinguldev.nexusspigot.api.NexusJsonDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageAuthTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final SecuritySettings SIGNED = new SecuritySettings("secret-key", false, 300_000L);
    private static final SecuritySettings UNSIGNED = new SecuritySettings("", false, 300_000L);

    private MessageValidationChain chain;

    @AfterEach
    void close() {
        if (chain != null) chain.close();
    }

    private static String samplePacket() {
        return new NexusJsonDataContainer()
                .set("protocol", 7)
                .set("type", "SET_DATA")
                .set("source", "lobby-1")
                .set("data", Map.of("uuid", "abc", "coins", 12.5, "tags", List.of("a", "b"),
                        "nested", Map.of("z", 1, "a", 2)))
                .toFullJson();
    }

    @Test
    void signedPacketPassesValidation() throws Exception {
        chain = MessageValidationChain.standard(SIGNED, LOGGER);
        String stamped = new MessageAuth(SIGNED).stamp(samplePacket());

        NexusJsonDataContainer packet = new NexusJsonDataContainer(stamped);
        assertNotNull(packet.getString("sig"));
        assertNotNull(packet.getString("nonce"));
        assertNotNull(packet.get("timestamp", Long.class));
        assertTrue(chain.runAll(packet));
    }

    @Test
    void replayedPacketIsRejected() throws Exception {
        chain = MessageValidationChain.standard(SIGNED, LOGGER);
        String stamped = new MessageAuth(SIGNED).stamp(samplePacket());

        assertTrue(chain.runAll(new NexusJsonDataContainer(stamped)));
        assertFalse(chain.runAll(new NexusJsonDataContainer(stamped)));
    }

    @Test
    void tamperedPacketIsRejected() throws Exception {
        chain = MessageValidationChain.standard(SIGNED, LOGGER);
        NexusJsonDataContainer packet = new NexusJsonDataContainer(new MessageAuth(SIGNED).stamp(samplePacket()));
        packet.set("protocol", 8);
        assertFalse(chain.runAll(packet));
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        chain = MessageValidationChain.standard(new SecuritySettings("other", false, 300_000L), LOGGER);
        assertFalse(chain.runAll(new NexusJsonDataContainer(new MessageAuth(SIGNED).stamp(samplePacket()))));
    }

    @Test
    void unsignedPacketStillGetsTimestampAndNonce() throws Exception {
        NexusJsonDataContainer packet = new NexusJsonDataContainer(new MessageAuth(UNSIGNED).stamp(samplePacket()));
        assertNull(packet.getString("sig"));
        assertNotNull(packet.getString("nonce"));
        assertNotNull(packet.get("timestamp", Long.class));
    }

    @Test
    void unsignedPacketsNeedExplicitOptIn() throws Exception {
        String stamped = new MessageAuth(UNSIGNED).stamp(samplePacket());

        chain = MessageValidationChain.standard(UNSIGNED, LOGGER);
        assertFalse(chain.runAll(new NexusJsonDataContainer(stamped)));
        chain.close();

        chain = MessageValidationChain.standard(new SecuritySettings("", true, 300_000L), LOGGER);
        assertTrue(chain.runAll(new NexusJsonDataContainer(stamped)));
    }

    @Test
    void oldTimestampIsRejected() throws Exception {
        chain = MessageValidationChain.standard(new SecuritySettings("", true, 1_000L), LOGGER);
        NexusJsonDataContainer packet = new NexusJsonDataContainer(new MessageAuth(UNSIGNED).stamp(samplePacket()));
        packet.set("timestamp", System.currentTimeMillis() - 60_000L);
        assertFalse(chain.runAll(packet));
    }

    @Test
    void signatureMatchesKnownVector() {
        // HMAC-SHA256("{}", "key"), base64
        assertEquals("p3dyTZQ+tI3Gm8qKSm1XoE2z+ex+HeTlgehgJlvfMDI=", HmacSigner.sign("{}", "key"));
    }
}