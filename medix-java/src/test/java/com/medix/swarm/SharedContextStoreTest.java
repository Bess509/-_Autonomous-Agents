package com.medix.swarm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class SharedContextStoreTest {
    @SuppressWarnings("unchecked")
    @Test
    void refreshesRedisTtlAfterHashWrite() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        SharedContextStore store = new SharedContextStore(redisTemplate, true, Duration.ofHours(2));

        store.put("s1", "route.mode", "SWARM");

        verify(hashOperations).put("medix:swarm:s1", "route.mode", "SWARM");
        verify(redisTemplate).expire("medix:swarm:s1", Duration.ofHours(2));
    }
}
