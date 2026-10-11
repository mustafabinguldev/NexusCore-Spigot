package io.github.mustafabinguldev.nexusspigot.redis;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.XAddParams;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pooled connection for writes plus one dedicated subscriber thread that
 * reconnects on its own. Only the channels this server needs are subscribed,
 * extra channels can be added while running.
 */
public final class RedisConnection {

    private static final long RECONNECT_DELAY_MS = 2000L;

    private final Logger logger;
    private final HostAndPort address;
    private final JedisClientConfig clientConfig;
    private final JedisPool pool;
    private final BiConsumer<String, String> messageConsumer;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private volatile JedisPubSub pubSub;
    private volatile Jedis subscriberJedis;
    private Thread subscriberThread;

    public RedisConnection(RedisSettings settings, Logger logger, BiConsumer<String, String> messageConsumer) {
        this.logger = logger;
        this.messageConsumer = messageConsumer;
        this.address = new HostAndPort(settings.host(), settings.port());

        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .database(settings.database())
                .timeoutMillis(settings.timeoutMs())
                .ssl(settings.ssl());
        if (settings.hasUser()) builder.user(settings.username());
        if (settings.hasPassword()) builder.password(settings.password());
        this.clientConfig = builder.build();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(2);
        poolConfig.setMaxWait(Duration.ofMillis(settings.timeoutMs()));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setJmxEnabled(false);

        this.pool = new JedisPool(poolConfig, address, clientConfig);
    }

    /** Throws if Redis cannot be reached, so startup can fail loudly. */
    public void ping() {
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        }
    }

    public boolean publish(String channel, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Redis PUBLISH to " + channel + " failed: " + e.getMessage());
            return false;
        }
    }

    /** XADD to a stream, used for every request that goes to NexusCore. */
    public boolean addToStream(String streamKey, String field, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamKey, XAddParams.xAddParams(), Map.of(field, message));
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Redis XADD to " + streamKey + " failed: " + e.getMessage());
            return false;
        }
    }

    public void subscribe(String channel) {
        if (!channels.add(channel)) return;
        JedisPubSub current = pubSub;
        if (current != null && current.isSubscribed()) {
            try {
                current.subscribe(channel);
            } catch (Exception e) {
                // The subscriber loop resubscribes to every channel after reconnecting.
                logger.fine("Late subscribe to " + channel + " deferred: " + e.getMessage());
            }
        }
    }

    public Set<String> channels() {
        return Set.copyOf(channels);
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        subscriberThread = new Thread(this::subscriberLoop, "NexusSpigot-Redis-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void subscriberLoop() {
        while (running) {
            try (Jedis jedis = new Jedis(address, clientConfig)) {
                subscriberJedis = jedis;
                JedisPubSub listener = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            messageConsumer.accept(channel, message);
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Error while handling message on " + channel, e);
                        }
                    }
                };
                pubSub = listener;
                jedis.subscribe(listener, channels.toArray(new String[0]));
            } catch (Exception e) {
                if (running) {
                    logger.warning("Redis subscriber disconnected (" + e.getMessage() + "), reconnecting in "
                            + RECONNECT_DELAY_MS + " ms...");
                }
            } finally {
                pubSub = null;
                subscriberJedis = null;
            }

            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;

        JedisPubSub current = pubSub;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (Exception ignored) {
            }
        }
        Jedis jedis = subscriberJedis;
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pool.close();
    }
}
