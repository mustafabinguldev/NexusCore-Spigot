package io.github.mustafabinguldev.nexusspigot.api;

import io.github.mustafabinguldev.nexusspigot.internal.AddonContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusAddonTest {

    static class Profile {
        @NexusField(id = true)
        private String uuid;
        @NexusField(defaultValue = "100")
        private int coins;
        @NexusField
        private double xp;
        @NexusField(defaultValue = "[\"starter\"]")
        private List<String> kits;
        @NexusField
        private Map<String, Integer> stats;
        private String notSynced = "local";
    }

    static class ProfileAddon extends NexusAddon<Profile> {
        public int protocolId() { return 42; }
        public String name() { return "profiles"; }
        public Class<Profile> dataClass() { return Profile.class; }
    }

    static class NoIdModel {
        @NexusField
        private String value;
    }

    static class FakeContext implements AddonContext {
        final List<NexusJsonDataContainer> sent = new ArrayList<>();
        final List<Boolean> queued = new ArrayList<>();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        long retry = 0;

        public String serverName() { return "lobby-1"; }
        public synchronized boolean sendToCore(NexusJsonDataContainer packet, boolean queueIfOffline) {
            sent.add(packet);
            queued.add(queueIfOffline);
            return true;
        }
        public ScheduledExecutorService scheduler() { return scheduler; }
        public long requestTimeoutMs() { return 2000; }
        public long retryIntervalMs() { return retry; }
        public Logger logger() { return Logger.getLogger("test"); }
        public boolean debug() { return false; }
    }

    private FakeContext ctx;
    private ProfileAddon addon;

    @BeforeEach
    void setUp() {
        ctx = new FakeContext();
        addon = new ProfileAddon();
        addon.attach(ctx);
    }

    @AfterEach
    void tearDown() {
        ctx.scheduler.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(NexusJsonDataContainer packet) {
        return (Map<String, Object>) packet.asMap().get("data");
    }

    @Test
    void setDataSendsAnnotatedFieldsWithDefaults() {
        Profile profile = new Profile();
        profile.uuid = "abc";
        profile.xp = 1.5;
        addon.setData(profile);

        NexusJsonDataContainer packet = ctx.sent.get(0);
        assertEquals(42, packet.get("protocol", Integer.class));
        assertEquals("SET_DATA", packet.getString("type"));
        assertEquals("lobby-1", packet.getString("source"));
        assertTrue(ctx.queued.get(0));

        Map<String, Object> data = data(packet);
        assertEquals("abc", data.get("uuid"));
        assertEquals(0, data.get("coins"));       // primitive int is never null
        assertEquals(1.5, data.get("xp"));
        assertEquals(List.of("starter"), data.get("kits"));
        assertTrue(data.containsKey("stats"));
        assertTrue(!data.containsKey("notSynced"));
    }

    @Test
    void toModelUsesDefaultsAndGenericTypes() throws Exception {
        NexusJsonDataContainer json = new NexusJsonDataContainer("{\"uuid\":\"abc\",\"xp\":3,\"stats\":{\"kills\":5}}");
        Profile profile = addon.toModel(json);

        assertEquals("abc", profile.uuid);
        assertEquals(100, profile.coins);
        assertEquals(3.0, profile.xp);
        assertEquals(List.of("starter"), profile.kits);
        assertEquals(Map.of("kills", 5), profile.stats);
        assertEquals("local", profile.notSynced);
    }

    @Test
    void getDataCompletesWhenBroadcastArrives() throws Exception {
        CompletableFuture<Profile> future = addon.getData("abc");
        CompletableFuture<Profile> second = addon.getData("abc");
        assertSame(future, second);

        NexusJsonDataContainer request = ctx.sent.get(0);
        assertEquals("GET_DATA", request.getString("type"));
        assertEquals(Map.of("uuid", "abc"), data(request));

        addon.handleData(new NexusJsonDataContainer("{\"uuid\":\"other\",\"coins\":1}"));
        assertTrue(!future.isDone());

        addon.handleData(new NexusJsonDataContainer("{\"uuid\":\"abc\",\"coins\":7}"));
        assertEquals(7, future.get(1, TimeUnit.SECONDS).coins);
    }

    @Test
    void getDataResendsUntilAnswered() throws Exception {
        ctx.retry = 50;
        CompletableFuture<Profile> future = addon.getData("abc", Duration.ofSeconds(2));
        Thread.sleep(200);
        int sentBefore;
        synchronized (ctx) {
            sentBefore = ctx.sent.size();
        }
        assertTrue(sentBefore >= 3, "expected retries, got " + sentBefore);

        addon.handleData(new NexusJsonDataContainer("{\"uuid\":\"abc\"}"));
        future.get(1, TimeUnit.SECONDS);
        Thread.sleep(150);
        synchronized (ctx) {
            assertTrue(ctx.sent.size() <= sentBefore + 1, "retries must stop after the answer");
        }
    }

    @Test
    void getDataTimesOut() {
        CompletableFuture<Profile> future = addon.getData("abc", Duration.ofMillis(100));
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, error.getCause());
        // A new request after a timeout gets a fresh future.
        assertTrue(addon.getData("abc") != future);
    }

    @Test
    void listenersReceiveEveryModel() throws Exception {
        List<String> seen = new ArrayList<>();
        addon.addListener(profile -> seen.add(profile.uuid));
        addon.handleData(new NexusJsonDataContainer("{\"uuid\":\"a\"}"));
        addon.handleData(new NexusJsonDataContainer("{\"uuid\":\"b\"}"));
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void incrementRemoveAndLoadCacheUseCoreFormat() {
        addon.increment("abc", "coins", 5);
        addon.remove("abc", true);
        addon.loadCache("abc");

        assertEquals("INCREMENT_DATA", ctx.sent.get(0).getString("type"));
        assertEquals(Map.of("key", "abc", "field", "coins", "amount", 5), data(ctx.sent.get(0)));

        assertEquals("REMOVE_DATA", ctx.sent.get(1).getString("type"));
        assertEquals(Map.of("uuid", "abc", "all", true), data(ctx.sent.get(1)));

        assertEquals("LOAD_CACHE", ctx.sent.get(2).getString("type"));
        assertEquals("abc", ctx.sent.get(2).getString("key"));
        assertTrue(ctx.queued.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void rankingAndPositionResponsesComplete() throws Exception {
        CompletableFuture<Map<Integer, Object>> ranking = addon.ranking("coins", SortOrder.DESC, 3);
        CompletableFuture<Integer> position = addon.rankPosition("abc", "coins", SortOrder.DESC);

        assertEquals(Map.of("field", "coins", "order", "DESC", "limit", 3), data(ctx.sent.get(0)));
        assertEquals(Map.of("field", "coins", "key", "abc", "order", "DESC"), data(ctx.sent.get(1)));

        Map<String, Object> response = new HashMap<>();
        response.put("2", "b");
        response.put("1", "a");
        addon.handleRanking(response);
        addon.handleRankPosition("abc", 4);

        assertEquals(Map.of(1, "a", 2, "b"), ranking.get(1, TimeUnit.SECONDS));
        assertEquals(4, position.get(1, TimeUnit.SECONDS));
    }

    @Test
    void modelWithoutIdIsRejected() {
        NexusAddon<NoIdModel> broken = new NexusAddon<>() {
            public int protocolId() { return 1; }
            public String name() { return "broken"; }
            public Class<NoIdModel> dataClass() { return NoIdModel.class; }
        };
        assertThrows(IllegalStateException.class, () -> broken.attach(ctx));
    }

    @Test
    void detachFailsPendingRequests() {
        CompletableFuture<Profile> future = addon.getData("abc");
        addon.detach();
        assertTrue(future.isCompletedExceptionally());
        assertThrows(IllegalStateException.class, () -> addon.getData("abc"));
    }

    @Test
    void nullStringFieldStaysNull() throws Exception {
        Profile profile = addon.toModel(new NexusJsonDataContainer("{\"coins\":1}"));
        assertNull(profile.uuid);
    }
}