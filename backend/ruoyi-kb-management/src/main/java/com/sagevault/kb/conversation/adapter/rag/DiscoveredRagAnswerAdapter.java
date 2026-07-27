package com.sagevault.kb.conversation.adapter.rag;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class DiscoveredRagAnswerAdapter implements RagAnswerPort {
    private final DiscoveryClient discovery;
    private final WebClient webClient;
    private final RagProperties properties;

    public DiscoveredRagAnswerAdapter(DiscoveryClient discovery, Builder webClient, RagProperties properties) {
        this.discovery = discovery;
        this.webClient = webClient.build();
        this.properties = properties;
    }

    @Override
    public Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId, String generationId) {
        ServiceInstance instance = discovery.getInstances(properties.serviceId()).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.RAG_UNAVAILABLE, "问答服务暂不可用"));
        long timestamp = Instant.now().getEpochSecond();
        RagAnswerRequest body = new RagAnswerRequest(knowledgeBaseId, question, requestId, generationId);
        return webClient.post().uri(instance.getUri() + "/internal/v1/answers")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Sage-Timestamp", Long.toString(timestamp))
                .header("X-Sage-Signature", sign(canonical(body, timestamp)))
                .bodyValue(body).retrieve().bodyToFlux(RagEvent.class).map(event -> map(event, generationId));
    }

    private AnswerEvent map(RagEvent event, String generationId) {
        if (!generationId.equals(event.generationId())) {
            throw new BusinessException(ErrorCode.RAG_UNAVAILABLE, "问答服务返回了错误的生成标识");
        }
        return switch (event.type()) {
            case "started" -> new AnswerEvent.Started(event.generationId());
            case "refused" -> new AnswerEvent.Refused(event.generationId(), event.message());
            default -> throw new BusinessException(ErrorCode.RAG_UNAVAILABLE, "问答服务返回了未知事件");
        };
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String canonical(RagAnswerRequest request, long timestamp) {
        return request.knowledgeBaseId() + ":" + request.requestId() + ":" + request.generationId() + ":"
                + timestamp + ":" + HexFormat.of().formatHex(sha256(request.question()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RagAnswerRequest(long knowledgeBaseId, String question, String requestId, String generationId) { }
    private record RagEvent(String type, String generationId, String message) { }
}
