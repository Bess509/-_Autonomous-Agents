package com.medix.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepSeekStreamingServiceTest {
    @Test
    void preservesReasoningAndAnswerAsSeparateProviderDeltas() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先核对证据\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"最终建议\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekStreamingService service = new DeepSeekStreamingService(true, "test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "deepseek-v4-flash", mapper);
            List<DeepSeekStreamingService.Delta> deltas = new ArrayList<>();
            service.stream("头痛怎么办", "多 Agent 草稿", deltas::add);
            assertThat(deltas).containsExactly(new DeepSeekStreamingService.Delta("先核对证据", ""),
                    new DeepSeekStreamingService.Delta("", "最终建议"));
            var request = mapper.readTree(requestBodies.getFirst());
            assertThat(request.path("stream").asBoolean()).isTrue();
            assertThat(request.path("thinking").path("type").asText()).isEqualTo("enabled");
        } finally {
            server.stop(0);
        }
    }
}
