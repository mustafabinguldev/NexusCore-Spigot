package io.github.mustafabinguldev.nexusspigot.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mustafabinguldev.nexusspigot.internal.AddonContext;
import io.github.mustafabinguldev.nexusspigot.util.NexusJson;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Spigot side of a NexusCore addon. {@link #protocolId()} and the
 * {@link NexusField} names of {@link #dataClass()} must match the addon
 * registered on NexusCore.
 *
 * <pre>{@code
 * public final class CoinAddon extends NexusAddon<CoinData> {
 *     public int protocolId() { return 10; }
 *     public String name() { return "coins"; }
 *     public Class<CoinData> dataClass() { return CoinData.class; }
 * }
 * }</pre>
 *
 * Futures complete on the main thread when {@code main-thread-callbacks} is
 * enabled, so never block the main thread with {@code join()} or {@code get()}.
 *
 * @param <T> data model with a no-arg constructor
 */
public abstract class NexusAddon<T> {

    private final Map<String, ModelField> fields = new LinkedHashMap<>();
    private ModelField idField;
    private Constructor<T> constructor;

    private volatile AddonContext context;

    private final Map<String, CompletableFuture<T>> pendingGets = new ConcurrentHashMap<>();
    private final Queue<CompletableFuture<Map<Integer, Object>>> pendingRankings = new ConcurrentLinkedQueue<>();
    private final Map<String, Queue<CompletableFuture<Integer>>> pendingPositions = new ConcurrentHashMap<>();
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    /** Protocol id of the matching NexusCore addon. */
    public abstract int protocolId();

    public abstract String name();

    public abstract Class<T> dataClass();

    /** Called for every model NexusCore sends to this server. */
    protected void onDataReceived(T data) {
    }

    /* ---------------------------------------------------------------------
       Requests
       --------------------------------------------------------------------- */

    /** Requests the model with the given id. Uses requests.timeout-ms from the config. */
    public CompletableFuture<T> getData(Object key) {
        return getData(key, Duration.ofMillis(requireContext().requestTimeoutMs()));
    }

    /**
     * Requests the model with the given id. NexusCore creates it with default
     * values if it does not exist yet. Concurrent requests for the same id share
     * one future. The request is re-sent every requests.retry-interval-ms until
     * an answer arrives; the future fails with {@link TimeoutException} after
     * {@code timeout}.
     */
    public CompletableFuture<T> getData(Object key, Duration timeout) {
        AddonContext ctx = requireContext();
        String id = String.valueOf(key);

        CompletableFuture<T> future = new CompletableFuture<>();
        CompletableFuture<T> existing = pendingGets.putIfAbsent(id, future);
        if (existing != null) return existing;

        long retry = ctx.retryIntervalMs();
        ScheduledFuture<?> resend = retry > 0
                ? ctx.scheduler().scheduleAtFixedRate(() -> sendGetRequest(key), retry, retry, TimeUnit.MILLISECONDS)
                : null;
        ScheduledFuture<?> timeoutTask = ctx.scheduler().schedule(() -> future.completeExceptionally(
                        new TimeoutException("No answer from NexusCore for " + name() + "/" + id)),
                timeout.toMillis(), TimeUnit.MILLISECONDS);

        future.whenComplete((value, error) -> {
            if (resend != null) resend.cancel(false);
            timeoutTask.cancel(false);
            pendingGets.remove(id, future);
        });

        sendGetRequest(key);
        return future;
    }

    /** Sends a single GET_DATA request. The answer arrives through listeners and {@link #onDataReceived}. */
    public void sendGetRequest(Object key) {
        Map<String, Object> data = new HashMap<>();
        data.put(idFieldName(), key);
        send("GET_DATA", data, false);
    }

    /** Creates or replaces the model on NexusCore. Queued while disconnected. */
    public void setData(T model) {
        send("SET_DATA", toData(model), true);
    }

    /** Atomically adds {@code amount} to a numeric field on NexusCore. Queued while disconnected. */
    public void increment(Object key, String field, Number amount) {
        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("amount", amount);
        send("INCREMENT_DATA", data, true);
    }

    /**
     * Removes the model from the NexusCore cache.
     *
     * @param fromDatabase also delete it from the database
     */
    public void remove(Object key, boolean fromDatabase) {
        Map<String, Object> data = new HashMap<>();
        data.put(idFieldName(), key);
        data.put("all", fromDatabase);
        send("REMOVE_DATA", data, true);
    }

    /** Asks NexusCore to warm its cache for the given id, e.g. on pre-login. */
    public void loadCache(Object key) {
        NexusJsonDataContainer packet = basePacket("LOAD_CACHE");
        packet.set("key", key);
        requireContext().sendToCore(packet, true);
    }

    /**
     * Top {@code limit} entries ordered by {@code field}, keyed by position (1 based).
     * NexusCore does not echo a request id, so concurrent ranking requests of the
     * same addon are answered in the order they were sent.
     */
    public CompletableFuture<Map<Integer, Object>> ranking(String field, SortOrder order, int limit) {
        CompletableFuture<Map<Integer, Object>> future = withTimeout(new CompletableFuture<>(), "ranking " + field);
        pendingRankings.add(future);
        future.whenComplete((value, error) -> pendingRankings.remove(future));

        Map<String, Object> data = new HashMap<>();
        data.put("field", field);
        data.put("order", order.name());
        data.put("limit", limit);
        if (!send("RANKING", data, false)) {
            future.completeExceptionally(new IllegalStateException("Ranking request could not be sent"));
        }
        return future;
    }

    /** Position (1 based) of {@code key} when ordered by {@code field}. */
    public CompletableFuture<Integer> rankPosition(Object key, String field, SortOrder order) {
        String id = String.valueOf(key);
        CompletableFuture<Integer> future = withTimeout(new CompletableFuture<>(), "rank position " + id);
        Queue<CompletableFuture<Integer>> queue = pendingPositions.computeIfAbsent(id, k -> new ConcurrentLinkedQueue<>());
        queue.add(future);
        future.whenComplete((value, error) -> queue.remove(future));

        Map<String, Object> data = new HashMap<>();
        data.put("field", field);
        data.put("key", key);
        data.put("order", order.name());
        if (!send("RANK_FINDER", data, false)) {
            future.completeExceptionally(new IllegalStateException("Rank position request could not be sent"));
        }
        return future;
    }

    /** Listener for every model of this addon that arrives on this server. */
    public void addListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    /* ---------------------------------------------------------------------
       Model mapping
       --------------------------------------------------------------------- */

    /** Converts a model to the field map NexusCore stores. Missing values use their defaults. */
    public Map<String, Object> toData(T model) {
        ensureInspected();
        Map<String, Object> data = new HashMap<>();
        for (ModelField field : fields.values()) {
            try {
                Object value = field.field.get(model);
                data.put(field.name, value != null ? value : field.defaultValue());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Field " + field.name + " of " + dataClass().getName() + " is not accessible", e);
            }
        }
        return data;
    }

    /** Builds a model from NexusCore data. Missing fields get their defaults. */
    public T toModel(NexusJsonDataContainer data) {
        ensureInspected();
        ObjectMapper mapper = NexusJson.mapper();
        Map<String, Object> values = data.asMap();
        try {
            T model = constructor.newInstance();
            for (ModelField field : fields.values()) {
                Object raw = values.get(field.name);
                Object value = raw != null ? mapper.convertValue(raw, field.javaType) : field.defaultValue();
                if (value == null && field.field.getType().isPrimitive()) continue;
                field.field.set(model, value);
            }
            return model;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Model " + dataClass().getName() + " could not be created", e);
        }
    }

    public String idFieldName() {
        ensureInspected();
        return idField.name;
    }

    /* ---------------------------------------------------------------------
       Called by the bridge. Not part of the API.
       --------------------------------------------------------------------- */

    public final void attach(AddonContext ctx) {
        if (context != null) {
            throw new IllegalStateException("Addon " + name() + " is already registered");
        }
        ensureInspected();
        this.context = ctx;
    }

    public final void detach() {
        context = null;
        CancellationException cancelled = new CancellationException("Addon " + name() + " was unregistered");
        pendingGets.values().forEach(future -> future.completeExceptionally(cancelled));
        pendingRankings.forEach(future -> future.completeExceptionally(cancelled));
        pendingPositions.values().forEach(queue -> queue.forEach(future -> future.completeExceptionally(cancelled)));
    }

    public final void handleData(NexusJsonDataContainer data) {
        T model = toModel(data);
        String id = data.getString(idField.name);

        CompletableFuture<T> waiting = id == null ? null : pendingGets.get(id);
        if (waiting != null) waiting.complete(model);

        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(model);
            } catch (Exception e) {
                logError("listener", e);
            }
        }
        try {
            onDataReceived(model);
        } catch (Exception e) {
            logError("onDataReceived", e);
        }
    }

    public final void handleRanking(Object response) {
        Map<Integer, Object> ranking = new TreeMap<>();
        if (response instanceof Map<?, ?> map) {
            map.forEach((position, value) -> ranking.put(Integer.parseInt(String.valueOf(position)), value));
        }
        Map<Integer, Object> result = Collections.unmodifiableMap(ranking);
        CompletableFuture<Map<Integer, Object>> future;
        while ((future = pendingRankings.poll()) != null) {
            if (future.complete(result)) return;
        }
    }

    public final void handleRankPosition(String key, Integer position) {
        Queue<CompletableFuture<Integer>> queue = pendingPositions.get(key);
        if (queue == null) return;
        CompletableFuture<Integer> future;
        while ((future = queue.poll()) != null) {
            if (future.complete(position)) break;
        }
        if (queue.isEmpty()) pendingPositions.remove(key, queue);
    }

    /* ---------------------------------------------------------------------
       Internals
       --------------------------------------------------------------------- */

    private boolean send(String type, Map<String, Object> data, boolean queueIfOffline) {
        NexusJsonDataContainer packet = basePacket(type);
        packet.set("data", data);
        return requireContext().sendToCore(packet, queueIfOffline);
    }

    private NexusJsonDataContainer basePacket(String type) {
        return new NexusJsonDataContainer()
                .set("protocol", protocolId())
                .set("type", type)
                .set("source", requireContext().serverName());
    }

    private <R> CompletableFuture<R> withTimeout(CompletableFuture<R> future, String what) {
        AddonContext ctx = requireContext();
        ScheduledFuture<?> timeoutTask = ctx.scheduler().schedule(() -> future.completeExceptionally(
                        new TimeoutException("No answer from NexusCore for " + name() + " " + what)),
                ctx.requestTimeoutMs(), TimeUnit.MILLISECONDS);
        future.whenComplete((value, error) -> timeoutTask.cancel(false));
        return future;
    }

    private AddonContext requireContext() {
        AddonContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("Addon " + name() + " is not registered. Call NexusBridge#registerAddon first.");
        }
        return ctx;
    }

    private void logError(String where, Exception e) {
        AddonContext ctx = context;
        if (ctx != null) ctx.logger().log(Level.SEVERE, "[" + name() + "] error in " + where, e);
    }

    private synchronized void ensureInspected() {
        if (idField != null) return;
        Class<T> type = dataClass();
        try {
            constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " needs a no-arg constructor", e);
        }

        Map<String, ModelField> found = new LinkedHashMap<>();
        ModelField id = null;
        ObjectMapper mapper = NexusJson.mapper();
        for (Field field : type.getDeclaredFields()) {
            NexusField annotation = field.getAnnotation(NexusField.class);
            if (annotation == null) continue;
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("@NexusField " + field.getName() + " must not be static or final");
            }
            field.setAccessible(true);

            JavaType javaType = mapper.getTypeFactory().constructType(field.getGenericType());
            ModelField modelField = new ModelField(field.getName(), field, javaType, annotation.defaultValue());
            modelField.defaultValue(); // fail fast on an invalid defaultValue
            found.put(modelField.name, modelField);

            if (annotation.id()) {
                if (id != null) {
                    throw new IllegalStateException(type.getName() + " has more than one @NexusField(id = true)");
                }
                id = modelField;
            }
        }
        if (id == null) {
            throw new IllegalStateException(type.getName() + " needs exactly one @NexusField(id = true)");
        }
        fields.putAll(found);
        idField = id;
    }

    private static final class ModelField {
        final String name;
        final Field field;
        final JavaType javaType;
        final String rawDefault;

        ModelField(String name, Field field, JavaType javaType, String rawDefault) {
            this.name = name;
            this.field = field;
            this.javaType = javaType;
            this.rawDefault = rawDefault;
        }

        Object defaultValue() {
            Class<?> type = javaType.getRawClass();
            if (rawDefault == null || rawDefault.isEmpty()) {
                if (type == int.class || type == Integer.class) return 0;
                if (type == long.class || type == Long.class) return 0L;
                if (type == double.class || type == Double.class) return 0.0D;
                if (type == float.class || type == Float.class) return 0.0F;
                if (type == short.class || type == Short.class) return (short) 0;
                if (type == byte.class || type == Byte.class) return (byte) 0;
                if (type == boolean.class || type == Boolean.class) return false;
                return null;
            }
            if (type == String.class) return rawDefault;
            try {
                if (type.isEnum() || type == char.class || type == Character.class) {
                    return NexusJson.mapper().convertValue(rawDefault, javaType);
                }
                // Numbers and booleans parse as JSON literals, complex types as JSON documents.
                return NexusJson.mapper().readValue(rawDefault, javaType);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid defaultValue for field " + name + ": " + rawDefault, e);
            }
        }
    }
}