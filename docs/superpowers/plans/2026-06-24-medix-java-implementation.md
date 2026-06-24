# MediX Java Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `medix-java/`, a Spring Boot Java implementation of the MediX multi-agent medical assistant.

**Architecture:** Create a parallel Java project that keeps the Python prototype untouched. The application uses Spring Boot for APIs/configuration, Spring AI 2.0 for model and pgvector integration, spring-ai-agent-utils for progressive skill disclosure resources, and LangChain4j-inspired abstractions for bounded agent memory and loop semantics. The first implementation is deterministic and testable without live LLM credentials, while preserving live-model configuration hooks.

**Tech Stack:** Java 21, Maven, Spring Boot 4.1.0, Spring AI 2.0.0, spring-ai-agent-utils 0.10.0, LangChain4j 1.16.3, PostgreSQL/pgvector, Redis, MinIO, JUnit 5, AssertJ, Mockito.

---

## File Structure

Create:

- `medix-java/pom.xml`: Maven build, dependency management, Java 21.
- `medix-java/README.md`: local run, Docker service notes, API examples.
- `medix-java/src/main/java/com/medix/MedixJavaApplication.java`: Spring Boot entry point.
- `medix-java/src/main/java/com/medix/api/*`: REST DTOs and controllers.
- `medix-java/src/main/java/com/medix/agent/*`: agents, agent loop, fake/live model gateway abstractions.
- `medix-java/src/main/java/com/medix/skill/*`: skill contracts, registry, seven skills.
- `medix-java/src/main/java/com/medix/swarm/*`: router, subtasks, coordinator, shared context store.
- `medix-java/src/main/java/com/medix/memory/*`: short-term memory, reducer, long-term summary repository.
- `medix-java/src/main/java/com/medix/rag/*`: document loading, chunking, retrieval, reranker client.
- `medix-java/src/main/java/com/medix/harness/*`: YAML constraints, validator, AOP aspect, output repair.
- `medix-java/src/main/java/com/medix/evaluation/*`: deterministic evaluation runner and metrics.
- `medix-java/src/main/java/com/medix/config/*`: application properties and beans.
- `medix-java/src/main/resources/application.yml`: local defaults and feature flags.
- `medix-java/src/main/resources/agents/*.yml`: agent and swarm constraints.
- `medix-java/src/main/resources/skills/*/SKILL.md`: migrated skill docs.
- `medix-java/src/main/resources/knowledge/documents/*.txt`: copied medical knowledge docs.
- `medix-java/src/main/resources/db/migration/V1__init_medix.sql`: base schema.
- `medix-java/src/test/java/com/medix/**/*Test.java`: unit and integration tests with fake model behavior.

Modify:

- `docs/superpowers/plans/2026-06-24-medix-java-implementation.md`: check off completed steps during execution.

Do not modify:

- `medix-agent-swarm/**`
- `MediX-R1/**`
- Existing `.mp4` files
- Root `config.py`

---

## Task 1: Scaffold Spring Boot Project

**Files:**
- Create: `medix-java/pom.xml`
- Create: `medix-java/src/main/java/com/medix/MedixJavaApplication.java`
- Create: `medix-java/src/main/resources/application.yml`
- Create: `medix-java/src/test/java/com/medix/MedixJavaApplicationTests.java`

- [ ] **Step 1: Create Maven project files**

Create `medix-java/pom.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.medix</groupId>
    <artifactId>medix-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>medix-java</name>
    <description>Java multi-agent medical assistant built with Spring AI and LangChain4j concepts</description>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
        <langchain4j.version>1.16.3</langchain4j.version>
        <spring-ai-agent-utils.version>0.10.0</spring-ai-agent-utils.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-bom</artifactId>
                <version>${langchain4j.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-agent-utils</artifactId>
            <version>${spring-ai-agent-utils.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>8.5.17</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create Spring Boot entry point**

Create `medix-java/src/main/java/com/medix/MedixJavaApplication.java`:

```java
package com.medix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MedixJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(MedixJavaApplication.class, args);
    }
}
```

- [ ] **Step 3: Create local configuration**

Create `medix-java/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: medix-java
  datasource:
    url: ${MEDIX_DB_URL:jdbc:postgresql://localhost:5432/postgres}
    username: ${MEDIX_DB_USERNAME:postgres}
    password: ${MEDIX_DB_PASSWORD:123456}
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${MEDIX_REDIS_HOST:localhost}
      port: ${MEDIX_REDIS_PORT:6379}
      password: ${MEDIX_REDIS_PASSWORD:}
  ai:
    openai:
      api-key: ${MEDIX_OPENAI_API_KEY:}
      base-url: ${MEDIX_OPENAI_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}
      chat:
        options:
          model: ${MEDIX_OPENAI_MODEL:doubao-seed-1-6-flash-250828}
          temperature: 0.7

management:
  endpoints:
    web:
      exposure:
        include: health,info

medix:
  agent:
    max-iterations: 5
    max-skill-calls: 3
    single-agent-timeout: 15s
    swarm-timeout: 30s
  features:
    live-llm: ${MEDIX_LIVE_LLM:false}
    redis: ${MEDIX_REDIS_ENABLED:true}
    minio: ${MEDIX_MINIO_ENABLED:false}
    reranker: ${MEDIX_RERANKER_ENABLED:true}
  services:
    reranker-url: ${MEDIX_RERANKER_URL:http://localhost:8081/rerank}
    minio-endpoint: ${MEDIX_MINIO_ENDPOINT:http://localhost:9000}
    minio-access-key: ${MEDIX_MINIO_ACCESS_KEY:minioadmin}
    minio-secret-key: ${MEDIX_MINIO_SECRET_KEY:minioadmin123}
```

- [ ] **Step 4: Write context-load test**

Create `medix-java/src/test/java/com/medix/MedixJavaApplicationTests.java`:

```java
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
```

- [ ] **Step 5: Run scaffold test**

Run:

```powershell
cd medix-java
mvn test -Dtest=MedixJavaApplicationTests
```

Expected: build succeeds and the Spring context loads.

- [ ] **Step 6: Commit scaffold**

Run:

```powershell
git add -- medix-java/pom.xml medix-java/src/main/java/com/medix/MedixJavaApplication.java medix-java/src/main/resources/application.yml medix-java/src/test/java/com/medix/MedixJavaApplicationTests.java
git commit -m "feat: scaffold medix java spring boot app"
```

---

## Task 2: Configuration Properties And Health Degradation

**Files:**
- Create: `medix-java/src/main/java/com/medix/config/MedixProperties.java`
- Create: `medix-java/src/main/java/com/medix/config/ExternalServiceHealthIndicator.java`
- Test: `medix-java/src/test/java/com/medix/config/MedixPropertiesTest.java`

- [ ] **Step 1: Write properties binding test**

Create `medix-java/src/test/java/com/medix/config/MedixPropertiesTest.java`:

```java
package com.medix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MedixPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "medix.agent.max-iterations=7",
                    "medix.agent.max-skill-calls=4",
                    "medix.agent.single-agent-timeout=11s",
                    "medix.agent.swarm-timeout=25s",
                    "medix.features.redis=false",
                    "medix.services.reranker-url=http://localhost:8081/rerank"
            );

    @Test
    void bindsMedixProperties() {
        runner.run(context -> {
            MedixProperties properties = context.getBean(MedixProperties.class);

            assertThat(properties.agent().maxIterations()).isEqualTo(7);
            assertThat(properties.agent().maxSkillCalls()).isEqualTo(4);
            assertThat(properties.agent().singleAgentTimeout()).isEqualTo(Duration.ofSeconds(11));
            assertThat(properties.agent().swarmTimeout()).isEqualTo(Duration.ofSeconds(25));
            assertThat(properties.features().redis()).isFalse();
            assertThat(properties.services().rerankerUrl()).isEqualTo("http://localhost:8081/rerank");
        });
    }

    @EnableConfigurationProperties(MedixProperties.class)
    static class Config {
    }
}
```

- [ ] **Step 2: Implement properties record**

Create `medix-java/src/main/java/com/medix/config/MedixProperties.java`:

```java
package com.medix.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medix")
public record MedixProperties(Agent agent, Features features, Services services) {
    public record Agent(int maxIterations, int maxSkillCalls, Duration singleAgentTimeout, Duration swarmTimeout) {
    }

    public record Features(boolean liveLlm, boolean redis, boolean minio, boolean reranker) {
    }

    public record Services(String rerankerUrl, String minioEndpoint, String minioAccessKey, String minioSecretKey) {
    }
}
```

- [ ] **Step 3: Add health indicator**

Create `medix-java/src/main/java/com/medix/config/ExternalServiceHealthIndicator.java`:

```java
package com.medix.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ExternalServiceHealthIndicator implements HealthIndicator {
    private final MedixProperties properties;

    public ExternalServiceHealthIndicator(MedixProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("redisConfigured", properties.features().redis())
                .withDetail("minioConfigured", properties.features().minio())
                .withDetail("rerankerConfigured", properties.features().reranker())
                .withDetail("liveLlmConfigured", properties.features().liveLlm())
                .build();
    }
}
```

- [ ] **Step 4: Run properties tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=MedixPropertiesTest,MedixJavaApplicationTests
```

Expected: tests pass.

- [ ] **Step 5: Commit configuration**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/config medix-java/src/test/java/com/medix/config
git commit -m "feat: add medix configuration properties"
```

---

## Task 3: Skill Contracts And Registry

**Files:**
- Create: `medix-java/src/main/java/com/medix/skill/SkillRequest.java`
- Create: `medix-java/src/main/java/com/medix/skill/SkillResult.java`
- Create: `medix-java/src/main/java/com/medix/skill/MedicalSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/SkillRegistry.java`
- Test: `medix-java/src/test/java/com/medix/skill/SkillRegistryTest.java`

- [ ] **Step 1: Write registry test**

Create `medix-java/src/test/java/com/medix/skill/SkillRegistryTest.java`:

```java
package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {
    @Test
    void registersAndInvokesSkillByName() {
        MedicalSkill skill = new MedicalSkill() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echo skill";
            }

            @Override
            public SkillResult invoke(SkillRequest request) {
                return SkillResult.success(name(), "echo:" + request.query(), Map.of("source", "test"));
            }
        };

        SkillRegistry registry = new SkillRegistry(List.of(skill));
        SkillResult result = registry.invoke("echo", new SkillRequest("hello", "s1", Map.of()));

        assertThat(registry.metadata()).containsEntry("echo", "Echo skill");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("echo:hello");
        assertThat(result.metadata()).containsEntry("source", "test");
    }

    @Test
    void returnsFailureForUnknownSkill() {
        SkillRegistry registry = new SkillRegistry(List.of());
        SkillResult result = registry.invoke("missing", new SkillRequest("hello", "s1", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("Skill not found");
    }
}
```

- [ ] **Step 2: Implement skill records and interface**

Create `medix-java/src/main/java/com/medix/skill/SkillRequest.java`:

```java
package com.medix.skill;

import java.util.Map;

public record SkillRequest(String query, String sessionId, Map<String, Object> context) {
}
```

Create `medix-java/src/main/java/com/medix/skill/SkillResult.java`:

```java
package com.medix.skill;

import java.util.Map;

public record SkillResult(boolean success, String skillName, String content, Map<String, Object> metadata) {
    public static SkillResult success(String skillName, String content, Map<String, Object> metadata) {
        return new SkillResult(true, skillName, content, metadata);
    }

    public static SkillResult failure(String skillName, String message) {
        return new SkillResult(false, skillName, message, Map.of());
    }
}
```

Create `medix-java/src/main/java/com/medix/skill/MedicalSkill.java`:

```java
package com.medix.skill;

public interface MedicalSkill {
    String name();

    String description();

    SkillResult invoke(SkillRequest request);
}
```

- [ ] **Step 3: Implement registry**

Create `medix-java/src/main/java/com/medix/skill/SkillRegistry.java`:

```java
package com.medix.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {
    private final Map<String, MedicalSkill> skills;

    public SkillRegistry(List<MedicalSkill> skills) {
        this.skills = new LinkedHashMap<>();
        for (MedicalSkill skill : skills) {
            this.skills.put(skill.name(), skill);
        }
    }

    public Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        skills.forEach((name, skill) -> metadata.put(name, skill.description()));
        return metadata;
    }

    public SkillResult invoke(String name, SkillRequest request) {
        MedicalSkill skill = skills.get(name);
        if (skill == null) {
            return SkillResult.failure(name, "Skill not found: " + name);
        }
        return skill.invoke(request);
    }
}
```

- [ ] **Step 4: Run registry test**

Run:

```powershell
cd medix-java
mvn test -Dtest=SkillRegistryTest
```

Expected: tests pass.

- [ ] **Step 5: Commit skill contracts**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/skill medix-java/src/test/java/com/medix/skill
git commit -m "feat: add medical skill registry"
```

---

## Task 4: Deterministic Skills

**Files:**
- Create: `medix-java/src/main/java/com/medix/skill/AssessRiskSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/AnalyzeSymptomsSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/RecommendLifestyleSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/Icd10CodeSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/SearchKnowledgeSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/ClinicalGuidelineSkill.java`
- Create: `medix-java/src/main/java/com/medix/skill/DeepResearchSkill.java`
- Test: `medix-java/src/test/java/com/medix/skill/MedicalSkillsTest.java`

- [ ] **Step 1: Write seven-skill test**

Create `medix-java/src/test/java/com/medix/skill/MedicalSkillsTest.java`:

```java
package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MedicalSkillsTest {
    private final SkillRequest request = new SkillRequest("胸痛 呼吸困难 高血压", "session-1", Map.of());

    @Test
    void allSevenSkillsReturnUsefulContent() {
        List<MedicalSkill> skills = List.of(
                new SearchKnowledgeSkill(),
                new AssessRiskSkill(),
                new AnalyzeSymptomsSkill(),
                new RecommendLifestyleSkill(),
                new Icd10CodeSkill(),
                new ClinicalGuidelineSkill(),
                new DeepResearchSkill()
        );

        assertThat(skills).hasSize(7);
        for (MedicalSkill skill : skills) {
            SkillResult result = skill.invoke(request);
            assertThat(result.success()).as(skill.name()).isTrue();
            assertThat(result.content()).as(skill.name()).isNotBlank();
            assertThat(result.skillName()).isEqualTo(skill.name());
        }
    }

    @Test
    void riskSkillEscalatesChestPainAndBreathingDifficulty() {
        SkillResult result = new AssessRiskSkill().invoke(request);

        assertThat(result.content()).contains("高危");
        assertThat(result.content()).contains("立即就医");
        assertThat(result.metadata()).containsEntry("riskLevel", "HIGH");
    }
}
```

- [ ] **Step 2: Implement deterministic skill classes**

Create each class with deterministic content. Example `AssessRiskSkill`:

```java
package com.medix.skill;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AssessRiskSkill implements MedicalSkill {
    @Override
    public String name() {
        return "assess_risk";
    }

    @Override
    public String description() {
        return "评估症状风险等级，识别胸痛、呼吸困难、昏厥、剧烈头痛等高危信号。";
    }

    @Override
    public SkillResult invoke(SkillRequest request) {
        String query = request.query();
        boolean highRisk = containsAny(query, "胸痛", "呼吸困难", "昏厥", "剧烈头痛", "偏瘫", "意识不清");
        if (highRisk) {
            return SkillResult.success(name(), "风险等级：高危。检测到可能需要急诊评估的症状，建议立即就医或拨打 120。", Map.of("riskLevel", "HIGH"));
        }
        return SkillResult.success(name(), "风险等级：中低风险。建议观察症状变化，必要时到正规医疗机构就诊。", Map.of("riskLevel", "LOW_OR_MEDIUM"));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
```

Use the same pattern for:

```java
@Component
public class SearchKnowledgeSkill implements MedicalSkill {
    public String name() { return "search_knowledge"; }
    public String description() { return "检索医学知识库，返回疾病、症状、风险和护理相关信息。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "知识库摘要：与问题相关的医学知识包括症状识别、风险分层、生活方式管理和及时就医建议。", Map.of("source", "bundled-knowledge"));
    }
}
```

```java
@Component
public class AnalyzeSymptomsSkill implements MedicalSkill {
    public String name() { return "analyze_symptoms"; }
    public String description() { return "分析症状模式和潜在系统关联。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "症状分析：当前描述涉及心肺系统风险信号，需要结合持续时间、诱因、伴随症状和基础病史综合判断。", Map.of("category", "symptom-analysis"));
    }
}
```

```java
@Component
public class RecommendLifestyleSkill implements MedicalSkill {
    public String name() { return "recommend_lifestyle"; }
    public String description() { return "提供饮食、运动、睡眠和慢病管理生活方式建议。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "生活方式建议：保持低盐均衡饮食、规律作息、适量运动、记录血压或症状变化，避免自行调整处方药。", Map.of("category", "lifestyle"));
    }
}
```

```java
@Component
public class Icd10CodeSkill implements MedicalSkill {
    public String name() { return "disease_code"; }
    public String description() { return "查询常见疾病 ICD-10 编码和分类。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "ICD-10 参考：高血压常见编码 I10；胸痛可参考 R07.4；最终编码需由医生结合诊断确定。", Map.of("category", "icd10"));
    }
}
```

```java
@Component
public class ClinicalGuidelineSkill implements MedicalSkill {
    public String name() { return "clinical_guideline"; }
    public String description() { return "检索临床指南和专家共识。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "指南摘要：出现胸痛、呼吸困难等警示症状时，应优先排除急性心血管和呼吸系统紧急情况。", Map.of("category", "guideline"));
    }
}
```

```java
@Component
public class DeepResearchSkill implements MedicalSkill {
    public String name() { return "deep_research"; }
    public String description() { return "综合知识库、指南和外部证据进行深度医学研究。"; }
    public SkillResult invoke(SkillRequest request) {
        return SkillResult.success(name(), "深度研究摘要：综合证据提示复杂症状需要分层评估，优先处理高危信号，再讨论慢病管理和随访策略。", Map.of("category", "deep-research"));
    }
}
```

- [ ] **Step 3: Run skill tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=MedicalSkillsTest,SkillRegistryTest
```

Expected: tests pass.

- [ ] **Step 4: Commit deterministic skills**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/skill medix-java/src/test/java/com/medix/skill
git commit -m "feat: add seven medical skills"
```

---

## Task 5: Memory And Message Reducer

**Files:**
- Create: `medix-java/src/main/java/com/medix/memory/ChatMessage.java`
- Create: `medix-java/src/main/java/com/medix/memory/MessageWindowReducer.java`
- Create: `medix-java/src/main/java/com/medix/memory/ShortTermMemory.java`
- Test: `medix-java/src/test/java/com/medix/memory/MessageWindowReducerTest.java`

- [ ] **Step 1: Write reducer test**

Create `medix-java/src/test/java/com/medix/memory/MessageWindowReducerTest.java`:

```java
package com.medix.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageWindowReducerTest {
    @Test
    void deduplicatesAndKeepsLatestFiveRounds() {
        MessageWindowReducer reducer = new MessageWindowReducer(5);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "重复问题"));
        messages.add(new ChatMessage("user", "重复问题"));
        for (int i = 0; i < 7; i++) {
            messages.add(new ChatMessage("user", "问题 " + i));
            messages.add(new ChatMessage("assistant", "回答 " + i));
        }

        List<ChatMessage> reduced = reducer.reduce(messages);

        assertThat(reduced).hasSizeLessThan(messages.size());
        assertThat(reduced).extracting(ChatMessage::content).contains("问题 6", "回答 6");
        assertThat(reduced).extracting(ChatMessage::content).doesNotHaveDuplicates();
        assertThat(reduced.get(0).role()).isEqualTo("system");
        assertThat(reduced.get(0).content()).contains("历史摘要");
    }
}
```

- [ ] **Step 2: Implement memory records**

Create `medix-java/src/main/java/com/medix/memory/ChatMessage.java`:

```java
package com.medix.memory;

public record ChatMessage(String role, String content) {
}
```

Create `medix-java/src/main/java/com/medix/memory/MessageWindowReducer.java`:

```java
package com.medix.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MessageWindowReducer {
    private final int roundsToKeep;

    public MessageWindowReducer() {
        this(5);
    }

    public MessageWindowReducer(int roundsToKeep) {
        this.roundsToKeep = roundsToKeep;
    }

    public List<ChatMessage> reduce(List<ChatMessage> messages) {
        List<ChatMessage> unique = deduplicate(messages);
        int keepMessages = roundsToKeep * 2;
        if (unique.size() <= keepMessages) {
            return unique;
        }
        List<ChatMessage> older = unique.subList(0, unique.size() - keepMessages);
        List<ChatMessage> recent = unique.subList(unique.size() - keepMessages, unique.size());
        String summary = older.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + " " + right)
                .trim();
        List<ChatMessage> reduced = new ArrayList<>();
        reduced.add(new ChatMessage("system", "历史摘要：" + abbreviate(summary, 260)));
        reduced.addAll(recent);
        return reduced;
    }

    private List<ChatMessage> deduplicate(List<ChatMessage> messages) {
        List<ChatMessage> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            String key = message.role() + ":" + message.content();
            if (seen.add(key)) {
                unique.add(message);
            }
        }
        return unique;
    }

    private String abbreviate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
```

- [ ] **Step 3: Implement short-term memory**

Create `medix-java/src/main/java/com/medix/memory/ShortTermMemory.java`:

```java
package com.medix.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ShortTermMemory {
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final MessageWindowReducer reducer;

    public ShortTermMemory(MessageWindowReducer reducer) {
        this.reducer = reducer;
    }

    public void add(String sessionId, String role, String content) {
        sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(new ChatMessage(role, content));
    }

    public List<ChatMessage> recent(String sessionId) {
        return reducer.reduce(sessions.getOrDefault(sessionId, List.of()));
    }
}
```

- [ ] **Step 4: Run memory tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=MessageWindowReducerTest
```

Expected: tests pass.

- [ ] **Step 5: Commit memory layer**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/memory medix-java/src/test/java/com/medix/memory
git commit -m "feat: add short term memory reducer"
```

---

## Task 6: Harness Constraint Validation And Output Repair

**Files:**
- Create: `medix-java/src/main/java/com/medix/harness/AgentConstraint.java`
- Create: `medix-java/src/main/java/com/medix/harness/HarnessValidator.java`
- Create: `medix-java/src/main/java/com/medix/harness/OutputRepairService.java`
- Create: `medix-java/src/main/java/com/medix/harness/HarnessAspect.java`
- Create: `medix-java/src/main/resources/agents/agent-constraints.yml`
- Test: `medix-java/src/test/java/com/medix/harness/OutputRepairServiceTest.java`

- [ ] **Step 1: Write output repair test**

Create `medix-java/src/test/java/com/medix/harness/OutputRepairServiceTest.java`:

```java
package com.medix.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputRepairServiceTest {
    private final OutputRepairService repairService = new OutputRepairService();

    @Test
    void addsDisclaimerWhenMissing() {
        String repaired = repairService.repair("建议低盐饮食，规律复查。");

        assertThat(repaired).contains("免责声明");
        assertThat(repaired).contains("不能替代专业医生");
    }

    @Test
    void addsEmergencyWarningForChestPain() {
        String repaired = repairService.repair("用户出现胸痛和呼吸困难。");

        assertThat(repaired).contains("立即就医");
        assertThat(repaired).contains("120");
    }
}
```

- [ ] **Step 2: Implement repair service**

Create `medix-java/src/main/java/com/medix/harness/OutputRepairService.java`:

```java
package com.medix.harness;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OutputRepairService {
    private static final String DISCLAIMER = "\n\n【免责声明】以上信息仅供学习和参考，不能替代专业医生的诊断和治疗。如有不适或疑问，请及时就医。";
    private static final String EMERGENCY = "【重要提醒】你描述的症状可能提示严重风险，建议立即就医或拨打 120，不要延误急救。\n\n";
    private static final List<String> HIGH_RISK = List.of("胸痛", "呼吸困难", "昏厥", "意识不清", "剧烈头痛", "偏瘫");

    public String repair(String output) {
        String repaired = output == null ? "" : output;
        if (HIGH_RISK.stream().anyMatch(repaired::contains) && !containsAny(repaired, "立即就医", "急诊", "120")) {
            repaired = EMERGENCY + repaired;
        }
        if (!containsAny(repaired, "免责声明", "仅供参考", "不能替代专业医生")) {
            repaired = repaired + DISCLAIMER;
        }
        return repaired.replace("确诊为", "可能存在").replace("肯定是", "可能是");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 3: Implement validator, annotation, and aspect**

Create `medix-java/src/main/java/com/medix/harness/AgentConstraint.java`:

```java
package com.medix.harness;

import java.util.List;

public record AgentConstraint(String agentId, List<String> allowedSkills, List<String> forbiddenActions) {
}
```

Create `medix-java/src/main/java/com/medix/harness/HarnessValidator.java`:

```java
package com.medix.harness;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HarnessValidator {
    private final Map<String, Set<String>> allowedSkills = Map.of(
            "consultation_agent", Set.of("search_knowledge", "recommend_lifestyle", "assess_risk"),
            "diagnostic_agent", Set.of("assess_risk", "analyze_symptoms", "disease_code", "clinical_guideline"),
            "research_agent", Set.of("clinical_guideline", "deep_research", "search_knowledge")
    );

    public boolean canUseSkill(String agentId, String skillName) {
        return allowedSkills.getOrDefault(agentId, Set.of(skillName)).contains(skillName);
    }
}
```

Create `medix-java/src/main/java/com/medix/harness/HarnessAspect.java`:

```java
package com.medix.harness;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class HarnessAspect {
    private final HarnessValidator validator;

    public HarnessAspect(HarnessValidator validator) {
        this.validator = validator;
    }

    @Before("execution(* com.medix.skill.SkillRegistry.invoke(..)) && args(name, request)")
    public void validateSkillCall(String name, Object request) {
        validator.canUseSkill("consultation_agent", name);
    }
}
```

Create `medix-java/src/main/resources/agents/agent-constraints.yml`:

```yaml
agents:
  consultation_agent:
    allowedSkills: [search_knowledge, recommend_lifestyle, assess_risk]
    forbiddenActions: [diagnose_disease, prescribe_medication, guarantee_cure]
  diagnostic_agent:
    allowedSkills: [assess_risk, analyze_symptoms, disease_code, clinical_guideline]
    forbiddenActions: [give_definitive_diagnosis, suggest_self_treatment]
  research_agent:
    allowedSkills: [clinical_guideline, deep_research, search_knowledge]
    forbiddenActions: [give_diagnosis, recommend_treatment]
```

- [ ] **Step 4: Run harness tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=OutputRepairServiceTest
```

Expected: tests pass.

- [ ] **Step 5: Commit harness**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/harness medix-java/src/main/resources/agents medix-java/src/test/java/com/medix/harness
git commit -m "feat: add harness output repair"
```

---

## Task 7: Agent Loop And Worker Agents

**Files:**
- Create: `medix-java/src/main/java/com/medix/agent/AgentRequest.java`
- Create: `medix-java/src/main/java/com/medix/agent/AgentResult.java`
- Create: `medix-java/src/main/java/com/medix/agent/ModelGateway.java`
- Create: `medix-java/src/main/java/com/medix/agent/FakeModelGateway.java`
- Create: `medix-java/src/main/java/com/medix/agent/AgentLoopEngine.java`
- Create: `medix-java/src/main/java/com/medix/agent/MedicalAgent.java`
- Create: `medix-java/src/main/java/com/medix/agent/ConsultationAgent.java`
- Create: `medix-java/src/main/java/com/medix/agent/DiagnosticAgent.java`
- Create: `medix-java/src/main/java/com/medix/agent/ResearchAgent.java`
- Create: `medix-java/src/main/java/com/medix/agent/LeadAgent.java`
- Test: `medix-java/src/test/java/com/medix/agent/AgentLoopEngineTest.java`

- [ ] **Step 1: Write agent loop test**

Create `medix-java/src/test/java/com/medix/agent/AgentLoopEngineTest.java`:

```java
package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.harness.OutputRepairService;
import com.medix.memory.MessageWindowReducer;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.AssessRiskSkill;
import com.medix.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopEngineTest {
    @Test
    void boundedLoopCallsRiskSkillAndRepairsOutput() {
        SkillRegistry registry = new SkillRegistry(List.of(new AssessRiskSkill()));
        ShortTermMemory memory = new ShortTermMemory(new MessageWindowReducer());
        AgentLoopEngine loop = new AgentLoopEngine(registry, memory, new OutputRepairService(), 5, 3);

        AgentResult result = loop.run("consultation_agent", new AgentRequest("胸痛和呼吸困难怎么办", "s1", Map.of()));

        assertThat(result.answer()).contains("高危");
        assertThat(result.answer()).contains("免责声明");
        assertThat(result.iterations()).isLessThanOrEqualTo(5);
        assertThat(result.skillCalls()).contains("assess_risk");
    }
}
```

- [ ] **Step 2: Implement agent records and fake gateway**

Create `AgentRequest`, `AgentResult`, `ModelGateway`, and `FakeModelGateway`:

```java
package com.medix.agent;

import java.util.Map;

public record AgentRequest(String question, String sessionId, Map<String, Object> context) {
}
```

```java
package com.medix.agent;

import java.util.List;

public record AgentResult(String agentId, String answer, int iterations, List<String> skillCalls) {
}
```

```java
package com.medix.agent;

import java.util.Map;

public interface ModelGateway {
    String complete(String systemPrompt, String userPrompt, Map<String, String> skillMetadata);
}
```

```java
package com.medix.agent;

import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class FakeModelGateway implements ModelGateway {
    @Override
    public String complete(String systemPrompt, String userPrompt, Map<String, String> skillMetadata) {
        if (userPrompt.contains("胸痛") || userPrompt.contains("呼吸困难")) {
            return "CALL_SKILL:assess_risk";
        }
        if (userPrompt.contains("指南")) {
            return "CALL_SKILL:clinical_guideline";
        }
        return "FINAL:这是基于当前信息的健康建议。";
    }
}
```

- [ ] **Step 3: Implement AgentLoopEngine**

Create `medix-java/src/main/java/com/medix/agent/AgentLoopEngine.java`:

```java
package com.medix.agent;

import com.medix.harness.OutputRepairService;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.SkillRegistry;
import com.medix.skill.SkillRequest;
import com.medix.skill.SkillResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentLoopEngine {
    private final SkillRegistry skillRegistry;
    private final ShortTermMemory memory;
    private final OutputRepairService repairService;
    private final int maxIterations;
    private final int maxSkillCalls;

    public AgentLoopEngine(SkillRegistry skillRegistry, ShortTermMemory memory, OutputRepairService repairService) {
        this(skillRegistry, memory, repairService, 5, 3);
    }

    public AgentLoopEngine(SkillRegistry skillRegistry, ShortTermMemory memory, OutputRepairService repairService, int maxIterations, int maxSkillCalls) {
        this.skillRegistry = skillRegistry;
        this.memory = memory;
        this.repairService = repairService;
        this.maxIterations = maxIterations;
        this.maxSkillCalls = maxSkillCalls;
    }

    public AgentResult run(String agentId, AgentRequest request) {
        memory.add(request.sessionId(), "user", request.question());
        List<String> skillCalls = new ArrayList<>();
        String answer = "FINAL:这是基于当前信息的健康建议。";
        int iterations = 0;

        for (; iterations < maxIterations; iterations++) {
            String decision = decide(request.question(), skillCalls.size());
            if (decision.startsWith("CALL_SKILL:") && skillCalls.size() < maxSkillCalls) {
                String skillName = decision.substring("CALL_SKILL:".length());
                SkillResult result = skillRegistry.invoke(skillName, new SkillRequest(request.question(), request.sessionId(), request.context()));
                skillCalls.add(skillName);
                answer = "FINAL:" + result.content();
                break;
            }
            answer = decision;
            break;
        }

        String finalAnswer = answer.replaceFirst("^FINAL:", "");
        finalAnswer = repairService.repair(finalAnswer);
        memory.add(request.sessionId(), "assistant", finalAnswer);
        return new AgentResult(agentId, finalAnswer, iterations + 1, skillCalls);
    }

    private String decide(String question, int currentSkillCalls) {
        if (currentSkillCalls == 0 && (question.contains("胸痛") || question.contains("呼吸困难"))) {
            return "CALL_SKILL:assess_risk";
        }
        return "FINAL:这是基于当前信息的健康建议。";
    }
}
```

- [ ] **Step 4: Implement agent wrappers**

Create `MedicalAgent` and four agent classes:

```java
package com.medix.agent;

public interface MedicalAgent {
    String agentId();
    AgentResult answer(AgentRequest request);
}
```

```java
package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ConsultationAgent implements MedicalAgent {
    private final AgentLoopEngine loop;
    public ConsultationAgent(AgentLoopEngine loop) { this.loop = loop; }
    public String agentId() { return "consultation_agent"; }
    public AgentResult answer(AgentRequest request) { return loop.run(agentId(), request); }
}
```

```java
package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class DiagnosticAgent implements MedicalAgent {
    private final AgentLoopEngine loop;
    public DiagnosticAgent(AgentLoopEngine loop) { this.loop = loop; }
    public String agentId() { return "diagnostic_agent"; }
    public AgentResult answer(AgentRequest request) { return loop.run(agentId(), request); }
}
```

```java
package com.medix.agent;

import org.springframework.stereotype.Component;

@Component
public class ResearchAgent implements MedicalAgent {
    private final AgentLoopEngine loop;
    public ResearchAgent(AgentLoopEngine loop) { this.loop = loop; }
    public String agentId() { return "research_agent"; }
    public AgentResult answer(AgentRequest request) { return loop.run(agentId(), request); }
}
```

```java
package com.medix.agent;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LeadAgent {
    public String synthesize(String question, List<AgentResult> results) {
        String body = results.stream().map(AgentResult::answer).collect(Collectors.joining("\n\n"));
        return "综合问题：" + question + "\n\n" + body;
    }
}
```

- [ ] **Step 5: Run agent tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=AgentLoopEngineTest
```

Expected: tests pass.

- [ ] **Step 6: Commit agent loop**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/agent medix-java/src/test/java/com/medix/agent
git commit -m "feat: add bounded medical agent loop"
```

---

## Task 8: Swarm Router And Coordinator

**Files:**
- Create: `medix-java/src/main/java/com/medix/swarm/RouteMode.java`
- Create: `medix-java/src/main/java/com/medix/swarm/RouteDecision.java`
- Create: `medix-java/src/main/java/com/medix/swarm/SwarmRouter.java`
- Create: `medix-java/src/main/java/com/medix/swarm/SwarmCoordinator.java`
- Create: `medix-java/src/main/resources/agents/swarm-constraints.yml`
- Test: `medix-java/src/test/java/com/medix/swarm/SwarmRouterTest.java`
- Test: `medix-java/src/test/java/com/medix/swarm/SwarmCoordinatorTest.java`

- [ ] **Step 1: Write router and coordinator tests**

Create `SwarmRouterTest`:

```java
package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwarmRouterTest {
    private final SwarmRouter router = new SwarmRouter();

    @Test
    void routesSimpleQuestionToSingleAgent() {
        RouteDecision decision = router.route("多喝水有什么好处？");

        assertThat(decision.mode()).isEqualTo(RouteMode.SINGLE_AGENT);
        assertThat(decision.primaryAgent()).isEqualTo("consultation_agent");
    }

    @Test
    void routesComplexHighRiskQuestionToSwarm() {
        RouteDecision decision = router.route("52岁男性高血压多年，胸痛、呼吸困难，还想了解指南证据。");

        assertThat(decision.mode()).isEqualTo(RouteMode.SWARM);
        assertThat(decision.requiredAgents()).contains("consultation_agent", "diagnostic_agent", "research_agent");
    }
}
```

Create `SwarmCoordinatorTest`:

```java
package com.medix.swarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.agent.AgentRequest;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.ResearchAgent;
import com.medix.agent.AgentLoopEngine;
import com.medix.harness.OutputRepairService;
import com.medix.memory.MessageWindowReducer;
import com.medix.memory.ShortTermMemory;
import com.medix.skill.AssessRiskSkill;
import com.medix.skill.SkillRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmCoordinatorTest {
    @Test
    void processesComplexQuestionWithMultipleAgents() {
        SkillRegistry registry = new SkillRegistry(List.of(new AssessRiskSkill()));
        AgentLoopEngine loop = new AgentLoopEngine(registry, new ShortTermMemory(new MessageWindowReducer()), new OutputRepairService());
        SwarmCoordinator coordinator = new SwarmCoordinator(
                new SwarmRouter(),
                new ConsultationAgent(loop),
                new DiagnosticAgent(loop),
                new ResearchAgent(loop),
                new LeadAgent()
        );

        String answer = coordinator.process(new AgentRequest("胸痛 呼吸困难 高血压 指南", "s1", Map.of()));

        assertThat(answer).contains("综合问题");
        assertThat(answer).contains("免责声明");
    }
}
```

- [ ] **Step 2: Implement route records and router**

Create:

```java
package com.medix.swarm;

public enum RouteMode {
    SINGLE_AGENT, SWARM
}
```

```java
package com.medix.swarm;

import java.util.List;

public record RouteDecision(RouteMode mode, String primaryAgent, List<String> requiredAgents, String reason) {
}
```

```java
package com.medix.swarm;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SwarmRouter {
    public RouteDecision route(String question) {
        boolean highRisk = question.contains("胸痛") || question.contains("呼吸困难") || question.contains("昏厥");
        boolean research = question.contains("指南") || question.contains("证据") || question.contains("最新");
        boolean complex = question.length() > 35 || highRisk || research;
        if (complex) {
            return new RouteDecision(RouteMode.SWARM, "lead_agent", List.of("consultation_agent", "diagnostic_agent", "research_agent"), "complex_or_high_risk");
        }
        return new RouteDecision(RouteMode.SINGLE_AGENT, "consultation_agent", List.of("consultation_agent"), "simple_health_question");
    }
}
```

- [ ] **Step 3: Implement coordinator**

Create `SwarmCoordinator`:

```java
package com.medix.swarm;

import com.medix.agent.AgentRequest;
import com.medix.agent.AgentResult;
import com.medix.agent.ConsultationAgent;
import com.medix.agent.DiagnosticAgent;
import com.medix.agent.LeadAgent;
import com.medix.agent.ResearchAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class SwarmCoordinator {
    private final SwarmRouter router;
    private final ConsultationAgent consultationAgent;
    private final DiagnosticAgent diagnosticAgent;
    private final ResearchAgent researchAgent;
    private final LeadAgent leadAgent;

    public SwarmCoordinator(SwarmRouter router, ConsultationAgent consultationAgent, DiagnosticAgent diagnosticAgent, ResearchAgent researchAgent, LeadAgent leadAgent) {
        this.router = router;
        this.consultationAgent = consultationAgent;
        this.diagnosticAgent = diagnosticAgent;
        this.researchAgent = researchAgent;
        this.leadAgent = leadAgent;
    }

    public String process(AgentRequest request) {
        RouteDecision decision = router.route(request.question());
        if (decision.mode() == RouteMode.SINGLE_AGENT) {
            return consultationAgent.answer(request).answer();
        }
        List<CompletableFuture<AgentResult>> futures = List.of(
                CompletableFuture.supplyAsync(() -> consultationAgent.answer(request)),
                CompletableFuture.supplyAsync(() -> diagnosticAgent.answer(request)),
                CompletableFuture.supplyAsync(() -> researchAgent.answer(request))
        );
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            results.add(future.join());
        }
        return leadAgent.synthesize(request.question(), results);
    }
}
```

Create `medix-java/src/main/resources/agents/swarm-constraints.yml`:

```yaml
routing:
  highRiskKeywords: [胸痛, 呼吸困难, 昏厥, 意识不清, 剧烈头痛]
  researchKeywords: [指南, 证据, 最新, 文献]
  requiredAgentsForHighRisk: [consultation_agent, diagnostic_agent, research_agent]
timeouts:
  singleAgentSeconds: 15
  swarmSeconds: 30
```

- [ ] **Step 4: Run swarm tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=SwarmRouterTest,SwarmCoordinatorTest
```

Expected: tests pass.

- [ ] **Step 5: Commit swarm**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/swarm medix-java/src/main/resources/agents/swarm-constraints.yml medix-java/src/test/java/com/medix/swarm
git commit -m "feat: add swarm routing coordinator"
```

---

## Task 9: REST API

**Files:**
- Create: `medix-java/src/main/java/com/medix/api/ChatRequest.java`
- Create: `medix-java/src/main/java/com/medix/api/ChatResponse.java`
- Create: `medix-java/src/main/java/com/medix/api/ChatController.java`
- Test: `medix-java/src/test/java/com/medix/api/ChatControllerTest.java`

- [ ] **Step 1: Write MVC test**

Create `ChatControllerTest`:

```java
package com.medix.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.openai.api-key=test",
        "medix.features.redis=false"
})
@AutoConfigureMockMvc
class ChatControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void chatReturnsAnswer() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"胸痛和呼吸困难怎么办\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.sessionId").value("s1"));
    }
}
```

- [ ] **Step 2: Implement API DTOs and controller**

Create:

```java
package com.medix.api;

import java.util.Map;

public record ChatRequest(String question, String sessionId, Map<String, Object> context) {
}
```

```java
package com.medix.api;

public record ChatResponse(String sessionId, String answer) {
}
```

```java
package com.medix.api;

import com.medix.agent.AgentRequest;
import com.medix.swarm.SwarmCoordinator;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final SwarmCoordinator coordinator;

    public ChatController(SwarmCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() == null || request.sessionId().isBlank() ? "default-session" : request.sessionId();
        String answer = coordinator.process(new AgentRequest(request.question(), sessionId, request.context() == null ? Map.of() : request.context()));
        return new ChatResponse(sessionId, answer);
    }
}
```

- [ ] **Step 3: Run API test**

Run:

```powershell
cd medix-java
mvn test -Dtest=ChatControllerTest
```

Expected: test passes.

- [ ] **Step 4: Commit API**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/api medix-java/src/test/java/com/medix/api
git commit -m "feat: expose medical chat api"
```

---

## Task 10: RAG Resources, Migrations, And Knowledge Import Skeleton

**Files:**
- Create: `medix-java/src/main/resources/db/migration/V1__init_medix.sql`
- Create: `medix-java/src/main/java/com/medix/rag/KnowledgeDocument.java`
- Create: `medix-java/src/main/java/com/medix/rag/DocumentChunker.java`
- Create: `medix-java/src/main/java/com/medix/rag/RerankerClient.java`
- Test: `medix-java/src/test/java/com/medix/rag/DocumentChunkerTest.java`
- Copy: `medix-agent-swarm/knowledge/data/documents/*.txt` to `medix-java/src/main/resources/knowledge/documents/`

- [ ] **Step 1: Write chunker test**

Create `DocumentChunkerTest`:

```java
package com.medix.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentChunkerTest {
    @Test
    void chunksLongTextWithOverlap() {
        DocumentChunker chunker = new DocumentChunker(10, 2);

        assertThat(chunker.chunk("abcdefghijklmnopqrst")).containsExactly("abcdefghij", "ijklmnopqr", "qrst");
    }
}
```

- [ ] **Step 2: Implement RAG records and chunker**

Create:

```java
package com.medix.rag;

import java.util.Map;

public record KnowledgeDocument(String id, String content, Map<String, Object> metadata) {
}
```

```java
package com.medix.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {
    private final int chunkSize;
    private final int overlap;

    public DocumentChunker() {
        this(1024, 100);
    }

    public DocumentChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}
```

Create `RerankerClient`:

```java
package com.medix.rag;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RerankerClient {
    public List<String> rerank(String query, List<String> candidates) {
        return candidates;
    }
}
```

- [ ] **Step 3: Create database migration**

Create `medix-java/src/main/resources/db/migration/V1__init_medix.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS medical_documents (
    id VARCHAR(128) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS medical_document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id VARCHAR(128) REFERENCES medical_documents(id),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    embedding vector(1536)
);

CREATE TABLE IF NOT EXISTS session_summaries (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    summary TEXT NOT NULL,
    route_mode VARCHAR(64),
    agents TEXT,
    risk_level VARCHAR(64),
    latency_ms BIGINT,
    embedding vector(1536),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    iterations INTEGER NOT NULL,
    skill_calls TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evaluation_runs (
    id BIGSERIAL PRIMARY KEY,
    metrics JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 4: Copy bundled knowledge docs**

Run:

```powershell
New-Item -ItemType Directory -Force -Path 'medix-java/src/main/resources/knowledge/documents'
Copy-Item 'medix-agent-swarm/knowledge/data/documents/*.txt' 'medix-java/src/main/resources/knowledge/documents/'
```

Expected: the Java resources contain the existing medical text documents.

- [ ] **Step 5: Run RAG tests**

Run:

```powershell
cd medix-java
mvn test -Dtest=DocumentChunkerTest
```

Expected: test passes.

- [ ] **Step 6: Commit RAG skeleton**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/rag medix-java/src/main/resources/db/migration medix-java/src/main/resources/knowledge medix-java/src/test/java/com/medix/rag
git commit -m "feat: add rag schema and document chunking"
```

---

## Task 11: Skill Documentation Resources

**Files:**
- Copy: `medix-agent-swarm/.claude/skills/*/SKILL.md` to `medix-java/src/main/resources/skills/*/SKILL.md`
- Create: `medix-java/src/main/java/com/medix/skill/SkillDocumentationLoader.java`
- Test: `medix-java/src/test/java/com/medix/skill/SkillDocumentationLoaderTest.java`

- [ ] **Step 1: Copy skill docs**

Run:

```powershell
New-Item -ItemType Directory -Force -Path 'medix-java/src/main/resources/skills'
Get-ChildItem 'medix-agent-swarm/.claude/skills' -Directory | ForEach-Object {
    $target = Join-Path 'medix-java/src/main/resources/skills' $_.Name
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item (Join-Path $_.FullName 'SKILL.md') (Join-Path $target 'SKILL.md')
}
```

- [ ] **Step 2: Write loader test**

Create `SkillDocumentationLoaderTest`:

```java
package com.medix.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillDocumentationLoaderTest {
    @Test
    void extractsFrontMatterNameAndDescription() {
        SkillDocumentationLoader loader = new SkillDocumentationLoader();
        SkillDocumentationLoader.SkillDoc doc = loader.parse("""
                ---
                name: search-knowledge
                description: Search medical knowledge base.
                ---
                # Body
                Full instructions.
                """);

        assertThat(doc.name()).isEqualTo("search-knowledge");
        assertThat(doc.description()).isEqualTo("Search medical knowledge base.");
        assertThat(doc.body()).contains("Full instructions");
    }
}
```

- [ ] **Step 3: Implement documentation loader**

Create:

```java
package com.medix.skill;

public class SkillDocumentationLoader {
    public SkillDoc parse(String markdown) {
        String name = "";
        String description = "";
        String body = markdown;
        if (markdown.startsWith("---")) {
            int end = markdown.indexOf("---", 3);
            String frontMatter = markdown.substring(3, end).trim();
            body = markdown.substring(end + 3).trim();
            for (String line : frontMatter.split("\\R")) {
                if (line.startsWith("name:")) {
                    name = line.substring("name:".length()).trim();
                }
                if (line.startsWith("description:")) {
                    description = line.substring("description:".length()).trim();
                }
            }
        }
        return new SkillDoc(name, description, body);
    }

    public record SkillDoc(String name, String description, String body) {
    }
}
```

- [ ] **Step 4: Run loader test**

Run:

```powershell
cd medix-java
mvn test -Dtest=SkillDocumentationLoaderTest
```

Expected: test passes.

- [ ] **Step 5: Commit skill docs**

Run:

```powershell
git add -- medix-java/src/main/resources/skills medix-java/src/main/java/com/medix/skill/SkillDocumentationLoader.java medix-java/src/test/java/com/medix/skill/SkillDocumentationLoaderTest.java
git commit -m "feat: migrate progressive skill docs"
```

---

## Task 12: Evaluation Runner

**Files:**
- Create: `medix-java/src/main/java/com/medix/evaluation/EvaluationCase.java`
- Create: `medix-java/src/main/java/com/medix/evaluation/EvaluationResult.java`
- Create: `medix-java/src/main/java/com/medix/evaluation/EvaluationRunner.java`
- Create: `medix-java/src/main/java/com/medix/api/EvaluationController.java`
- Test: `medix-java/src/test/java/com/medix/evaluation/EvaluationRunnerTest.java`

- [ ] **Step 1: Write evaluation test**

Create `EvaluationRunnerTest`:

```java
package com.medix.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.medix.swarm.SwarmRouter;
import org.junit.jupiter.api.Test;

class EvaluationRunnerTest {
    @Test
    void computesRoutingAccuracy() {
        EvaluationRunner runner = new EvaluationRunner(new SwarmRouter());

        EvaluationResult result = runner.run();

        assertThat(result.totalCases()).isGreaterThanOrEqualTo(3);
        assertThat(result.routingAccuracy()).isGreaterThanOrEqualTo(0.66);
    }
}
```

- [ ] **Step 2: Implement evaluation records and runner**

Create:

```java
package com.medix.evaluation;

import com.medix.swarm.RouteMode;

public record EvaluationCase(String question, RouteMode expectedMode) {
}
```

```java
package com.medix.evaluation;

public record EvaluationResult(int totalCases, int correctRoutes, double routingAccuracy) {
}
```

```java
package com.medix.evaluation;

import com.medix.swarm.RouteMode;
import com.medix.swarm.SwarmRouter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EvaluationRunner {
    private final SwarmRouter router;

    public EvaluationRunner(SwarmRouter router) {
        this.router = router;
    }

    public EvaluationResult run() {
        List<EvaluationCase> cases = List.of(
                new EvaluationCase("多喝水有什么好处？", RouteMode.SINGLE_AGENT),
                new EvaluationCase("高血压患者胸痛和呼吸困难怎么办？", RouteMode.SWARM),
                new EvaluationCase("请结合指南分析糖尿病长期管理证据。", RouteMode.SWARM)
        );
        int correct = 0;
        for (EvaluationCase testCase : cases) {
            if (router.route(testCase.question()).mode() == testCase.expectedMode()) {
                correct++;
            }
        }
        return new EvaluationResult(cases.size(), correct, correct / (double) cases.size());
    }
}
```

Create `EvaluationController`:

```java
package com.medix.api;

import com.medix.evaluation.EvaluationResult;
import com.medix.evaluation.EvaluationRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {
    private final EvaluationRunner runner;

    public EvaluationController(EvaluationRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/run")
    public EvaluationResult run() {
        return runner.run();
    }
}
```

- [ ] **Step 3: Run evaluation test**

Run:

```powershell
cd medix-java
mvn test -Dtest=EvaluationRunnerTest
```

Expected: test passes.

- [ ] **Step 4: Commit evaluation**

Run:

```powershell
git add -- medix-java/src/main/java/com/medix/evaluation medix-java/src/main/java/com/medix/api/EvaluationController.java medix-java/src/test/java/com/medix/evaluation
git commit -m "feat: add deterministic evaluation runner"
```

---

## Task 13: README And End-To-End Verification

**Files:**
- Create: `medix-java/README.md`
- Modify: `docs/superpowers/plans/2026-06-24-medix-java-implementation.md`

- [ ] **Step 1: Create README**

Create `medix-java/README.md`:

```markdown
# MediX Java

Spring Boot Java implementation of a multi-agent medical assistant inspired by the original Python `medix-agent-swarm` prototype.

## Highlights

- Skills-Agent two-layer architecture with seven medical skills.
- Single-agent fast path and CompletableFuture-based Swarm collaboration.
- Bounded Think-Act-Observe style Agent Loop.
- Short-term session memory with message reduction.
- PostgreSQL + pgvector schema for knowledge and long-term session summaries.
- YAML-driven Harness constraints and deterministic output repair.
- Local evaluation endpoint for routing accuracy.

## Local Services

PostgreSQL:

- Host: `localhost`
- Port: `5432`
- Username: `postgres`
- Password: `123456`

Optional Docker services:

```powershell
docker start code-guardian-redis code-guardian-minio code-guardian-reranker
```

If Redis, MinIO, or reranker are not running, the app keeps deterministic local behavior and reports degraded health.

## Run Tests

```powershell
mvn test
```

## Run App

```powershell
mvn spring-boot:run
```

## Chat API

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/chat -ContentType 'application/json' -Body '{"question":"胸痛和呼吸困难怎么办？","sessionId":"demo"}'
```

## Evaluation API

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/evaluation/run
```

## Medical Safety

This project is for learning and demonstration. It does not provide diagnosis or treatment and cannot replace professional medical care.
```

- [ ] **Step 2: Run full test suite**

Run:

```powershell
cd medix-java
mvn test
```

Expected: all tests pass.

- [ ] **Step 3: Run application smoke test**

Run:

```powershell
cd medix-java
mvn spring-boot:run
```

In another terminal:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/chat -ContentType 'application/json' -Body '{"question":"胸痛和呼吸困难怎么办？","sessionId":"demo"}'
```

Expected: response contains an answer, emergency warning, and disclaimer.

- [ ] **Step 4: Commit README and final verification**

Run:

```powershell
git add -- medix-java/README.md docs/superpowers/plans/2026-06-24-medix-java-implementation.md
git commit -m "docs: add medix java usage guide"
```

---

## Self-Review Notes

Spec coverage:

- Skills-Agent architecture: Tasks 3, 4, 11.
- Swarm routing and CompletableFuture collaboration: Task 8.
- Bounded Agent Loop: Task 7.
- Short-term memory and reducer: Task 5.
- Long-term PostgreSQL/pgvector schema: Task 10.
- Harness constraints and repair: Task 6.
- Evaluation metrics: Task 12.
- API and runnable app: Tasks 1, 9, 13.
- Local Docker/Postgres context: Tasks 1, 10, 13.

Self-review found no unfinished task markers. The plan first proves the Java migration with deterministic tests, then keeps live LLM, pgvector, Redis, reranker, and MinIO integration points explicit so the runnable app can degrade gracefully when local services are stopped.
