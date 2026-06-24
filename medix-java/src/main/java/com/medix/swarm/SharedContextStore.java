package com.medix.swarm;

import com.medix.config.MedixProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SharedContextStore {
    private static final String KEY_PREFIX = "medix:swarm:";

    private final Map<String, Map<String, String>> local = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redisTemplate;
    private final boolean redisEnabled;

    public SharedContextStore() {
        this.redisTemplate = Optional.empty();
        this.redisEnabled = false;
    }

    @Autowired
    public SharedContextStore(ObjectProvider<StringRedisTemplate> redisTemplate, MedixProperties properties) {
        this.redisTemplate = Optional.ofNullable(redisTemplate.getIfAvailable());
        this.redisEnabled = properties.features().redis();
    }

    public void put(String sessionId, String key, String value) {
        local.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>()).put(key, value);
        if (!redisEnabled || redisTemplate.isEmpty()) {
            return;
        }
        try {
            redisTemplate.get().opsForHash().put(redisKey(sessionId), key, value);
        } catch (RuntimeException ignored) {
            local.computeIfAbsent(sessionId, current -> new ConcurrentHashMap<>()).put("redis.status", "unavailable");
        }
    }

    public Map<String, String> entries(String sessionId) {
        Map<String, String> entries = new LinkedHashMap<>(local.getOrDefault(sessionId, Map.of()));
        if (!redisEnabled || redisTemplate.isEmpty()) {
            return entries;
        }
        try {
            redisTemplate.get().opsForHash().entries(redisKey(sessionId))
                    .forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
        } catch (RuntimeException ignored) {
            entries.put("redis.status", "unavailable");
        }
        return entries;
    }

    private String redisKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
