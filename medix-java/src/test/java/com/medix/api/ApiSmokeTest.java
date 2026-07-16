package com.medix.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.medix.permission.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=test",
        "medix.features.redis=false",
        "medix.features.reranker=false",
        "medix.features.minio=false"
})
class ApiSmokeTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private PermissionService permissions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void chatEndpointReturnsSwarmAnswerAndMemoryEntropyMetrics() throws Exception {
        String cookie = login();
        String question = "chest pain breathing difficulty with research context and repeated follow up";
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/chat"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "sessionId": "api-test",
                          "question": "%s",
                          "context": {"age": 52}
                        }
                        """.formatted(question)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"routeMode\":\"SWARM\"");
        assertThat(response.body()).contains("\"primaryAgent\":\"diagnostic_agent\"");
        assertThat(response.body()).contains("\"route.reason\":\"emergency_rule\"");
        assertThat(response.body()).contains("\"nlu.status\":\"skipped_emergency\"");
        assertThat(response.body()).contains("\"answer\"");

        HttpRequest entropyRequest = HttpRequest.newBuilder(uri("/api/v1/memory/entropy/api-test")).header("Cookie", cookie).GET().build();
        HttpResponse<String> entropyResponse = httpClient.send(entropyRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(entropyResponse.statusCode()).isEqualTo(200);
        assertThat(entropyResponse.body()).contains("totalMessages");
        assertThat(entropyResponse.body()).contains("entropyLevel");
        assertThat(entropyResponse.body()).doesNotContain(question);

        HttpRequest evaluationRequest = HttpRequest.newBuilder(uri("/api/v1/evaluation/summary")).header("Cookie", cookie).GET().build();
        HttpResponse<String> evaluationResponse = httpClient.send(evaluationRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(evaluationResponse.statusCode()).isEqualTo(200);
        assertThat(evaluationResponse.body()).contains("memoryEntropy");
        assertThat(evaluationResponse.body()).contains("trackedSessions");
    }

    @Test
    void skillsEndpointExposesProgressiveDisclosure() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/skills")).header("Cookie", login()).GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("progressiveDisclosure");
        assertThat(response.body()).contains("search_knowledge");
        assertThat(response.body()).contains("assess_risk");
    }

    @Test
    void aguiRequiresAuthenticationAndReturnsIdempotentCoreEvents() throws Exception {
        String body = """
                {"threadId":"agui-thread","runId":"agui-run","state":{},
                 "messages":[{"id":"m1","role":"user","content":"请给我普通健康建议"}],
                 "tools":[],"context":[],"forwardedProps":{"agentId":"consultation_agent"}}
                """;
        HttpRequest anonymous = HttpRequest.newBuilder(uri("/api/v1/agui")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(httpClient.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        String cookie = login();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/agui")).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> first = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> duplicate = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(duplicate.body()).isEqualTo(first.body());
    }

    @Test
    void headacheIsConsistentAcrossHttpAndStableAguiEvents() throws Exception {
        String cookie = login();
        String suffix = String.valueOf(System.nanoTime());
        HttpRequest chat = HttpRequest.newBuilder(uri("/api/v1/chat"))
                .header("Content-Type", "application/json").header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"sessionId":"http-headache-%s","question":"我有点头痛","context":{}}
                        """.formatted(suffix))).build();
        HttpResponse<String> chatResponse = httpClient.send(chat, HttpResponse.BodyHandlers.ofString());
        assertThat(chatResponse.statusCode()).isEqualTo(200);
        String httpAnswer = mapper.readTree(chatResponse.body()).path("answer").asText();

        String runId = "agui-headache-" + suffix;
        String body = """
                {"threadId":"headache-%s","runId":"%s","state":{},
                 "messages":[{"id":"m1","role":"user","content":"我有点头痛"}],
                 "tools":[],"context":[],"forwardedProps":{"agentId":"diagnostic_agent"}}
                """.formatted(suffix, runId);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/agui"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> first = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> replay = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        List<JsonNode> events = parseEvents(first.body());
        Set<String> eventIds = new HashSet<>();
        long expectedSequence = 1;
        String messageId = null;
        StringBuilder streamed = new StringBuilder();
        int starts = 0;
        int ends = 0;
        for (JsonNode event : events) {
            assertThat(event.path("runId").asText()).isEqualTo(runId);
            assertThat(event.path("sequence").asLong()).isEqualTo(expectedSequence++);
            assertThat(eventIds.add(event.path("eventId").asText())).isTrue();
            if ("TEXT_MESSAGE_START".equals(event.path("type").asText())) {
                starts++;
                messageId = event.path("messageId").asText();
            }
            if ("TEXT_MESSAGE_CONTENT".equals(event.path("type").asText())) {
                assertThat(event.path("messageId").asText()).isEqualTo(messageId);
                streamed.append(event.path("delta").asText());
            }
            if ("TEXT_MESSAGE_END".equals(event.path("type").asText())) ends++;
        }
        assertThat(starts).isEqualTo(1);
        assertThat(ends).isEqualTo(1);
        assertThat(streamed.toString()).isEqualTo(httpAnswer)
                .contains("【证据摘要】", "【综合建议】", "补充水分", "【免责声明】")
                .doesNotContain("disease_code", "I10", "R07.4", "Observation", "metadata");
        assertThat(count(streamed.toString(), "【免责声明】")).isEqualTo(1);
        assertThat(first.body()).contains("analyze_symptoms", "assess_risk").doesNotContain("disease_code");
    }

    @Test
    void permissionsAreEnforcedServerSideAndThreadsAreOwnerScoped() throws Exception {
        String threadId = "owned-" + System.nanoTime();
        String adminBody = aguiBody(threadId, "admin-" + System.nanoTime(), "consultation_agent");
        HttpRequest create = HttpRequest.newBuilder(uri("/api/v1/agui")).header("Content-Type", "application/json")
                .header("Cookie", login()).POST(HttpRequest.BodyPublishers.ofString(adminBody)).build();
        assertThat(httpClient.send(create, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        String demoCookie = login("demo", "user-change-me");
        HttpRequest steal = HttpRequest.newBuilder(uri("/api/v1/agui")).header("Content-Type", "application/json")
                .header("Cookie", demoCookie).POST(HttpRequest.BodyPublishers.ofString(
                        aguiBody(threadId, "steal-" + System.nanoTime(), "consultation_agent"))).build();
        HttpResponse<String> stealResponse = httpClient.send(steal, HttpResponse.BodyHandlers.ofString());
        assertThat(stealResponse.statusCode()).withFailMessage(stealResponse.body()).isEqualTo(403);

        HttpRequest diagnostic = HttpRequest.newBuilder(uri("/api/v1/agui")).header("Content-Type", "application/json")
                .header("Cookie", demoCookie).POST(HttpRequest.BodyPublishers.ofString(
                        aguiBody("demo-" + System.nanoTime(), "denied-" + System.nanoTime(), "diagnostic_agent"))).build();
        assertThat(httpClient.send(diagnostic, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void emergencyStillReturnsFixedSafetyAdviceWhenDiagnosticAgentIsNotGranted() throws Exception {
        String demoCookie = login("demo", "user-change-me");
        HttpRequest emergency = HttpRequest.newBuilder(uri("/api/v1/agui"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Cookie", demoCookie)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"threadId":"emergency-%s","runId":"emergency-run-%s","state":{},
                         "messages":[{"id":"m1","role":"user","content":"chest pain and difficulty breathing"}],
                         "tools":[],"context":[],"forwardedProps":{"agentId":"consultation_agent"}}
                        """.formatted(System.nanoTime(), System.nanoTime())))
                .build();

        HttpResponse<String> response = httpClient.send(emergency, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("RUN_FINISHED", "120");
        assertThat(response.body()).doesNotContain("RUN_ERROR");
    }

    @Test
    void adminCanReadPersistentPermissionDataAndUnsafeMcpEndpointIsRejected() throws Exception {
        String cookie = login();
        HttpRequest users = HttpRequest.newBuilder(uri("/api/v1/admin/users")).header("Cookie", cookie).GET().build();
        HttpResponse<String> usersResponse = httpClient.send(users, HttpResponse.BodyHandlers.ofString());
        assertThat(usersResponse.statusCode()).isEqualTo(200);
        assertThat(usersResponse.body()).contains("admin", "demo", "consultation_agent");

        HttpRequest unsafeMcp = HttpRequest.newBuilder(uri("/api/v1/admin/mcp-servers"))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"name":"unsafe","transport":"STREAMABLE_HTTP","endpoint":"https://127.0.0.1/mcp"}
                        """)).build();
        assertThat(httpClient.send(unsafeMcp, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void effectivePermissionMatrixCannotBypassDisabledCapabilitiesOrHarness() {
        jdbc.update("UPDATE capabilities SET enabled = false WHERE id = 'search_knowledge'");
        jdbc.update("""
                INSERT INTO agent_capability_grants(agent_id, capability_id, action)
                VALUES ('consultation_agent', 'analyze_symptoms', 'EXECUTE')
                ON CONFLICT (agent_id, capability_id, action) DO NOTHING
                """);
        try {
            assertThat(permissions.matrix().get("consultation_agent"))
                    .doesNotContain("search_knowledge", "analyze_symptoms")
                    .contains("assess_risk", "recommend_lifestyle");
        } finally {
            jdbc.update("UPDATE capabilities SET enabled = true WHERE id = 'search_knowledge'");
            jdbc.update("""
                    DELETE FROM agent_capability_grants
                    WHERE agent_id = 'consultation_agent'
                      AND capability_id = 'analyze_symptoms'
                      AND action = 'EXECUTE'
                    """);
        }
    }

    @Test
    void effectivePermissionMatrixRequiresTheCapabilitySpecificAction() {
        jdbc.update("""
                DELETE FROM agent_capability_grants
                WHERE agent_id = 'consultation_agent'
                  AND capability_id = 'assess_risk'
                  AND action = 'EXECUTE'
                """);
        jdbc.update("""
                INSERT INTO agent_capability_grants(agent_id, capability_id, action)
                VALUES ('consultation_agent', 'assess_risk', 'READ')
                ON CONFLICT (agent_id, capability_id, action) DO NOTHING
                """);
        try {
            assertThat(permissions.matrix().get("consultation_agent")).doesNotContain("assess_risk");
        } finally {
            jdbc.update("""
                    DELETE FROM agent_capability_grants
                    WHERE agent_id = 'consultation_agent'
                      AND capability_id = 'assess_risk'
                      AND action = 'READ'
                    """);
            jdbc.update("""
                    INSERT INTO agent_capability_grants(agent_id, capability_id, action)
                    VALUES ('consultation_agent', 'assess_risk', 'EXECUTE')
                    ON CONFLICT (agent_id, capability_id, action) DO NOTHING
                    """);
        }
    }

    @Test
    void registrationAndPasswordChangeAreAvailableEndToEnd() throws Exception {
        String username = "user_" + System.nanoTime();
        String initialPassword = "Health1234";
        String newPassword = "Safer5678";
        String registerBody = """
                {"username":"%s","password":"%s","displayName":"新用户"}
                """.formatted(username, initialPassword);
        HttpRequest register = HttpRequest.newBuilder(uri("/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(registerBody)).build();
        HttpResponse<String> registered = httpClient.send(register, HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).isEqualTo(200);
        assertThat(registered.body()).contains(username, "新用户", "USER");
        String cookie = registered.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];

        HttpResponse<String> duplicate = httpClient.send(register, HttpResponse.BodyHandlers.ofString());
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("USERNAME_TAKEN");

        HttpRequest wrongCurrent = HttpRequest.newBuilder(uri("/api/v1/auth/password"))
                .header("Content-Type", "application/json").header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"currentPassword":"wrong-password","newPassword":"%s"}
                        """.formatted(newPassword))).build();
        assertThat(httpClient.send(wrongCurrent, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

        HttpRequest change = HttpRequest.newBuilder(uri("/api/v1/auth/password"))
                .header("Content-Type", "application/json").header("Cookie", cookie)
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"currentPassword":"%s","newPassword":"%s"}
                        """.formatted(initialPassword, newPassword))).build();
        HttpResponse<String> changed = httpClient.send(change, HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("\"changed\":true");
        assertThat(changed.headers().firstValue("Set-Cookie")).hasValueSatisfying(value ->
                assertThat(value).contains("Max-Age=0"));

        HttpRequest oldLogin = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s"}
                        """.formatted(username, initialPassword))).build();
        assertThat(httpClient.send(oldLogin, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(login(username, newPassword)).startsWith("MEDIX_SESSION=");
        jdbc.update("DELETE FROM app_users WHERE username = ?", username);
    }

    @Test
    void ownerCanDeleteSingleAndMultipleThreadsButCannotDeleteAnotherUsersThread() throws Exception {
        String username = "cleanup_" + System.nanoTime();
        String password = "Cleanup123";
        HttpRequest register = HttpRequest.newBuilder(uri("/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s","displayName":"清理测试"}
                        """.formatted(username, password))).build();
        HttpResponse<String> registration = httpClient.send(register, HttpResponse.BodyHandlers.ofString());
        String cookie = registration.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        String suffix = String.valueOf(System.nanoTime());
        String first = "delete-one-" + suffix;
        String second = "delete-two-" + suffix;
        String third = "delete-three-" + suffix;
        for (String id : List.of(first, second, third)) {
            HttpRequest create = HttpRequest.newBuilder(uri("/api/v1/agui"))
                    .header("Content-Type", "application/json").header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.ofString(aguiBody(id, "run-" + id, "consultation_agent"))).build();
            assertThat(httpClient.send(create, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }

        HttpRequest single = HttpRequest.newBuilder(uri("/api/v1/me/threads/" + first))
                .header("Cookie", cookie).DELETE().build();
        HttpResponse<String> singleResult = httpClient.send(single, HttpResponse.BodyHandlers.ofString());
        assertThat(singleResult.statusCode()).isEqualTo(200);
        assertThat(singleResult.body()).contains("\"deleted\":1");

        String adminThread = "admin-owned-" + suffix;
        HttpRequest adminCreate = HttpRequest.newBuilder(uri("/api/v1/agui"))
                .header("Content-Type", "application/json").header("Cookie", login())
                .POST(HttpRequest.BodyPublishers.ofString(aguiBody(adminThread, "run-" + adminThread, "consultation_agent"))).build();
        assertThat(httpClient.send(adminCreate, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        HttpRequest forbidden = HttpRequest.newBuilder(uri("/api/v1/me/threads/" + adminThread))
                .header("Cookie", cookie).DELETE().build();
        assertThat(httpClient.send(forbidden, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);

        HttpRequest bulk = HttpRequest.newBuilder(uri("/api/v1/me/threads"))
                .header("Content-Type", "application/json").header("Cookie", cookie)
                .method("DELETE", HttpRequest.BodyPublishers.ofString("""
                        {"threadIds":["%s","%s"]}
                        """.formatted(second, third))).build();
        HttpResponse<String> bulkResult = httpClient.send(bulk, HttpResponse.BodyHandlers.ofString());
        assertThat(bulkResult.statusCode()).isEqualTo(200);
        assertThat(bulkResult.body()).contains("\"deleted\":2");
        HttpRequest remaining = HttpRequest.newBuilder(uri("/api/v1/me/threads")).header("Cookie", cookie).GET().build();
        assertThat(httpClient.send(remaining, HttpResponse.BodyHandlers.ofString()).body())
                .doesNotContain(first, second, third);
        jdbc.update("DELETE FROM agent_runs WHERE thread_id = ?", adminThread);
        jdbc.update("DELETE FROM conversation_threads WHERE id = ?", adminThread);
        jdbc.update("DELETE FROM app_users WHERE username = ?", username);
    }

    @Test
    void disablingAccountInvalidatesExistingJwtOnNextRequest() throws Exception {
        String demoCookie = login("demo", "user-change-me");
        jdbc.update("UPDATE app_users SET status = 'DISABLED' WHERE username = 'demo'");
        try {
            HttpRequest me = HttpRequest.newBuilder(uri("/api/v1/auth/me"))
                    .header("Cookie", demoCookie).GET().build();
            assertThat(httpClient.send(me, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        } finally {
            jdbc.update("UPDATE app_users SET status = 'ACTIVE' WHERE username = 'demo'");
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String login() throws Exception {
        return login("admin", "admin-change-me");
    }

    private String login(String username, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private String aguiBody(String threadId, String runId, String agentId) {
        return """
                {"threadId":"%s","runId":"%s","state":{},
                 "messages":[{"id":"m1","role":"user","content":"请给我普通健康建议"}],
                 "tools":[],"context":[],"forwardedProps":{"agentId":"%s"}}
                """.formatted(threadId, runId, agentId);
    }

    private List<JsonNode> parseEvents(String sse) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String frame : sse.split("\\R\\R")) {
            for (String line : frame.split("\\R")) {
                if (line.startsWith("data: ")) events.add(mapper.readTree(line.substring(6)));
            }
        }
        return events;
    }

    private int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
