package com.medix;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.openai.api-key=test",
        "medix.features.redis=false"
})
class MedixJavaApplicationTests {
    @Test
    void contextLoads() {
    }
}
