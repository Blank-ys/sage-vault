package com.sagevault.kb.conversation.adapter.rag;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.conversation.domain.AnswerEvent;
import com.sagevault.kb.conversation.service.port.RagAnswerPort;
import com.sagevault.kb.qarecord.domain.RetrievedChunkDiagnostic;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DiscoveredRagAnswerAdapter implements RagAnswerPort {
    private static final Logger log = LoggerFactory.getLogger(DiscoveredRagAnswerAdapter.class);

    private final DiscoveryClient discovery;
    private final WebClient webClient;
    private final RagProperties properties;
    /** 生成到承载它的 RAG 实例的亲和表：取消是显式业务命令，必须回到原实例。 */
    private final Map<String, URI> owningInstances = new ConcurrentHashMap<>();

    public DiscoveredRagAnswerAdapter(DiscoveryClient discovery, Builder webClient, RagProperties properties) {
        this.discovery = discovery;
        this.webClient = webClient.build();
        this.properties = properties;
    }

    @Override
    public Flux<AnswerEvent> answer(long knowledgeBaseId, String question, String requestId, String generationId) {
        ServiceInstance instance = discovery.getInstances(properties.serviceId()).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.RAG_UNAVAILABLE, "问答服务暂不可用"));
        URI owner = instance.getUri();
        long timestamp = Instant.now().getEpochSecond();
        RagAnswerRequest body = new RagAnswerRequest(knowledgeBaseId, question, requestId, generationId);
        owningInstances.put(generationId, owner);
        return webClient.post().uri(owner + "/internal/v1/answers")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Sage-Timestamp", Long.toString(timestamp))
                .header("X-Sage-Signature", sign(canonical(body, timestamp)))
                .bodyValue(body).retrieve().bodyToFlux(RagEvent.class).map(event -> map(event, generationId))
                .doFinally(signal -> owningInstances.remove(generationId));
    }

    @Override
    public Mono<Boolean> cancel(String generationId, String requestId) {
        URI owner = owningInstances.get(generationId);
        if (owner == null) {
            log.info("Skip RAG cancel, no owning instance known: generationId={} requestId={}",
                    generationId, requestId);
            return Mono.just(false);
        }
        long timestamp = Instant.now().getEpochSecond();
        RagCancelRequest body = new RagCancelRequest(generationId, requestId);
        return webClient.post().uri(owner + "/internal/v1/answers/" + generationId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Sage-Timestamp", Long.toString(timestamp))
                .header("X-Sage-Signature", sign(cancelCanonical(body, timestamp)))
                .bodyValue(body).retrieve().bodyToMono(RagCancelAck.class)
                .map(RagCancelAck::cancelled)
                .onErrorResume(error -> {
                    log.warn("Best-effort RAG cancel failed: generationId={} requestId={}",
                            generationId, requestId, error);
                    return Mono.just(false);
                });
    }

    private AnswerEvent map(RagEvent event, String generationId) {
        if (!generationId.equals(event.generationId())) {
            throw new BusinessException(ErrorCode.RAG_UNAVAILABLE, "问答服务返回了错误的生成标识");
        }
        return switch (event.type()) {
            case "started" -> new AnswerEvent.Started(event.generationId());
            case "delta" -> new AnswerEvent.Delta(event.generationId(), event.delta());
            case "completed" -> new AnswerEvent.Completed(
                    event.generationId(),
                    event.retrievalDiagnostics() == null
                            ? List.of() : event.retrievalDiagnostics(),
                    event.stageDurations() == null
                            ? Map.of() : event.stageDurations());
            case "refused" -> new AnswerEvent.Refused(event.generationId(), event.message());
            case "stopped" -> new AnswerEvent.Stopped(event.generationId());
            case "failed" -> {
                // 诊断只留在网关日志，detail 已是脱敏后的受控失败类别，不得回传原始异常/知识库 id。
                log.warn("RAG answer failed: generationId={} detail={}", generationId, event.detail());
                yield new AnswerEvent.Failed(generationId, event.detail());
            }
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

    private static String cancelCanonical(RagCancelRequest request, long timestamp) {
        return "cancel:" + request.generationId() + ":" + request.requestId() + ":" + timestamp;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RagAnswerRequest(long knowledgeBaseId, String question, String requestId, String generationId) { }
    private record RagCancelRequest(String generationId, String requestId) { }
    private record RagCancelAck(String generationId, boolean cancelled) { }
    private record RagEvent(
            String type,
            String generationId,
            String delta,
            String message,
            String detail,
            List<RetrievedChunkDiagnostic> retrievalDiagnostics,
            Map<String, Integer> stageDurations) { }
}
