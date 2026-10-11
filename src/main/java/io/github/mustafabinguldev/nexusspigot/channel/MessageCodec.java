package io.github.mustafabinguldev.nexusspigot.channel;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mustafabinguldev.nexusspigot.util.NexusJson;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reflection metadata of one message type. */
final class MessageCodec<T extends ChannelMessage> {

    static final String KEY_TAG = "tag";
    static final String KEY_ID = "id";
    static final String KEY_SOURCE = "source";

    private final String tag;
    private final Constructor<T> constructor;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, JavaType> types = new HashMap<>();

    MessageCodec(Class<T> type) {
        MessageTag messageTag = type.getAnnotation(MessageTag.class);
        if (messageTag == null) {
            throw new IllegalArgumentException(type.getName() + " has no @MessageTag");
        }
        this.tag = messageTag.value();
        try {
            this.constructor = type.getDeclaredConstructor();
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName() + " needs a no-arg constructor", e);
        }

        ObjectMapper mapper = NexusJson.mapper();
        for (Class<?> current = type; current != ChannelMessage.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                MessageField messageField = field.getAnnotation(MessageField.class);
                if (messageField == null || Modifier.isStatic(field.getModifiers())) continue;
                String key = messageField.value();
                if (key.equals(KEY_TAG) || key.equals(KEY_ID) || key.equals(KEY_SOURCE)) {
                    throw new IllegalArgumentException("@MessageField key '" + key + "' is reserved");
                }
                field.setAccessible(true);
                fields.putIfAbsent(key, field);
                types.putIfAbsent(key, mapper.getTypeFactory().constructType(field.getGenericType()));
            }
        }
    }

    String tag() {
        return tag;
    }

    Map<String, Object> encode(T message) {
        Map<String, Object> json = new HashMap<>();
        json.put(KEY_TAG, tag);
        json.put(KEY_ID, message.id);
        json.put(KEY_SOURCE, message.source);
        try {
            for (Map.Entry<String, Field> entry : fields.entrySet()) {
                Object value = entry.getValue().get(message);
                if (value != null) json.put(entry.getKey(), value);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Message " + tag + " could not be encoded", e);
        }
        return json;
    }

    T decode(Map<String, Object> json) {
        ObjectMapper mapper = NexusJson.mapper();
        try {
            T message = constructor.newInstance();
            message.id = stringOrNull(json.get(KEY_ID));
            message.source = stringOrNull(json.get(KEY_SOURCE));
            for (Map.Entry<String, Field> entry : fields.entrySet()) {
                Object raw = json.get(entry.getKey());
                if (raw == null) continue;
                entry.getValue().set(message, mapper.convertValue(raw, types.get(entry.getKey())));
            }
            return message;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Message " + tag + " could not be decoded", e);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}