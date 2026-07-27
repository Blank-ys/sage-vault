package com.sagevault.kb.conversation.adapter.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.reactive.function.client.WebClient;

class DiscoveredRagAnswerAdapterTest {
    private HttpServer provider;
    private boolean receivedSignature;
    private boolean validSignature;

    @BeforeEach
    void startProvider() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/internal/v1/answers", exchange -> {
            String timestamp = exchange.getRequestHeaders().getFirst("X-Sage-Timestamp");
            String signature = exchange.getRequestHeaders().getFirst("X-Sage-Signature");
            receivedSignature = signature != null;
            String questionHash = sha256("问题");
            validSignature = signature != null && signature.equals(sign("1:req-1:gen-1:" + timestamp + ":" + questionHash));
            String events = "event: started\ndata: {\"type\":\"started\",\"generationId\":\"gen-1\"}\n\n"
                    + "event: refused\ndata: {\"type\":\"refused\",\"generationId\":\"gen-1\","
                    + "\"message\":\"该知识库暂无可用文档\"}\n\n";
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
    }

    @AfterEach
    void stopProvider() {
        provider.stop(0);
    }

    @Test
    void discoversProviderSignsRequestAndMapsRegisteredEvents() {
        DiscoveryClient discovery = new FixedDiscoveryClient(provider.getAddress().getPort());
        var adapter = new DiscoveredRagAnswerAdapter(discovery, WebClient.builder(),
                new RagProperties("sage-vault-rag", "test-key"));

        List<AnswerEvent> events = adapter.answer(1, "问题", "req-1", "gen-1").collectList().block();

        assertThat(receivedSignature).isTrue();
        assertThat(validSignature).isTrue();
        assertThat(events).containsExactly(
                new AnswerEvent.Started("gen-1"),
                new AnswerEvent.Refused("gen-1", "该知识库暂无可用文档"));
    }

    @Test
    void rejectsEventForAnotherGeneration() {
        DiscoveryClient discovery = new FixedDiscoveryClient(provider.getAddress().getPort());
        var adapter = new DiscoveredRagAnswerAdapter(discovery, WebClient.builder(),
                new RagProperties("sage-vault-rag", "test-key"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> adapter.answer(1, "问题", "req-1", "other-generation").collectList().block()))
                .hasMessageContaining("错误的生成标识");
    }

    private static String sign(String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC test setup is unavailable", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 test setup is unavailable", exception);
        }
    }

    private record FixedDiscoveryClient(int port) implements DiscoveryClient {
        @Override public String description() { return "test discovery"; }
        @Override public List<ServiceInstance> getInstances(String serviceId) {
            return List.of(new DefaultServiceInstance("rag-1", serviceId, "127.0.0.1", port, false));
        }
        @Override public List<String> getServices() { return List.of("sage-vault-rag"); }
    }
}
