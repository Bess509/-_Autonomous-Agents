package com.medix.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MedicalRagEmbeddingClientTest {
    @Test
    void sendsQueryToDedicatedEndpointAndValidates1024Dimensions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            String vector = "0.1,".repeat(1023) + "0.1";
            byte[] body = ("{\"embeddings\":[[" + vector + "]]}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            MedicalRagEmbeddingClient client = new MedicalRagEmbeddingClient(HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://localhost:" + server.getAddress().getPort(), "bge-m3", 1024, Duration.ofSeconds(2));
            assertThat(client.embedQuery("二甲双胍副作用")).hasSize(1024).containsOnly(0.1);
        } finally {
            server.stop(0);
        }
    }
}
