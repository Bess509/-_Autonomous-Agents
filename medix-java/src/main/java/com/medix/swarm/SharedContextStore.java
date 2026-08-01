package com.medix.swarm;

import com.medix.config.MedixProperties;
import com.medix.memory.MemoryProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SharedContextStore {
    private static final Logger log = LoggerFactory.getLogger(SharedContextStore.class);
    private static final String KEY_PREFIX = "medix:swarm:";

    private final Map<String, Map<String, String>> local = new ConcurrentHashMap<>();
    private final Optional<StringRedisTemplate> redisTemplate;
    private final boolean redisEnabled;
    private final Duration redisContextTtl;

    public SharedContextStore() {
        this.redisTemplate = Optional.empty();
        this.redisEnabled = false;
        this.redisContextTtl = Duration.ofHours(2);
    }

    @Autowired
    public SharedContextStore(
            ObjectProvider<StringRedisTemplate> redisTemplate,
            MedixProperties properties,
            MemoryProperties memoryProperties
    ) {
        this.redisTemplate = Optional.ofNullable(redisTemplate.getIfAvailable());
        this.redisEnabled = properties.features().redis();
        this.redisContextTtl = memoryProperties.redisContextTtl();
    }

    SharedContextStore(StringRedisTemplate redisTemplate, boolean redisEnabled, Duration redisContextTtl) {
        this.redisTemplate = Optional.ofNullable(redisTemplate);
        this.redisEnabled = redisEnabled;
        this.redisContextTtl = redisContextTtl == null ? Duration.ofHours(2) : redisContextTtl;
    }

    public void put(String sessionId, String key, String value) {
        local.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>()).put(key, value);
        if (!redisEnabled || redisTemplate.isEmpty()) {
            return;
        }
        try {
            String redisKey = redisKey(sessionId);
            redisTemplate.get().opsForHash().put(redisKey, key, value);
            redisTemplate.get().expire(redisKey, redisContextTtl);
        } catch (RuntimeException ignored) {
            log.warn("[FALLBACK] component=SHARED_CONTEXT reason=redis_write_unavailable session={}", sessionId);
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
            log.warn("[FALLBACK] component=SHARED_CONTEXT reason=redis_read_unavailable session={}", sessionId);
            entries.put("redis.status", "unavailable");
        }
        return entries;
    }

    private String redisKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
