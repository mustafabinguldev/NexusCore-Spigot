package io.github.mustafabinguldev.nexusspigot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mustafabinguldev.nexusspigot.util.NexusJson;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key-sorted JSON object, the wire format NexusCore speaks.
 */
public class NexusJsonDataContainer {

    private final Map<String, Object> dataMap = new TreeMap<>();
    private final ObjectMapper mapper = NexusJson.mapper();

    public NexusJsonDataContainer() {
    }

    public NexusJsonDataContainer(String json) throws JsonProcessingException {
        Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (map != null) {
            dataMap.putAll(map);
        }
    }

    public NexusJsonDataContainer(Map<String, ?> values) {
        dataMap.putAll(values);
    }

    public <T> T get(String key, Class<T> clazz) {
        Object value = dataMap.get(key);
        if (value == null) return null;
        if (clazz.isInstance(value)) return clazz.cast(value);
        return mapper.convertValue(value, clazz);
    }

    public String getString(String key) {
        Object value = dataMap.get(key);
        return value == null ? null : value.toString();
    }

    public NexusJsonDataContainer set(String key, Object value) {
        dataMap.put(key, value);
        return this;
    }

    public NexusJsonDataContainer remove(String key) {
        dataMap.remove(key);
        return this;
    }

    public boolean containsKey(String key) {
        return dataMap.containsKey(key);
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(dataMap);
    }

    public String toFullJson() {
        try {
            return mapper.writeValueAsString(dataMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Container could not be serialized", e);
        }
    }

    @Override
    public String toString() {
        return toFullJson();
    }
}
