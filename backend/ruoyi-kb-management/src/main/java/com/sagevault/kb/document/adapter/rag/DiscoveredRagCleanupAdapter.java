package com.sagevault.kb.document.adapter.rag;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class DiscoveredRagCleanupAdapter implements CleanupCommandDispatcher {

    private final DiscoveryClient discovery;
    private final WebClient webClient;
    private final RagProperties properties;

    public DiscoveredRagCleanupAdapter(DiscoveryClient discovery, WebClient.Builder webClient,
            RagProperties properties) {
        this.discovery = discovery;
        this.webClient = webClient.build();
        this.properties = properties;
    }

    @Override
    public void dispatch(DocumentEntity document) {
        ServiceInstance instance = discovery.getInstances(properties.serviceId()).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"));
        long timestamp = Instant.now().getEpochSecond();
        String taskId = "cleanup-" + document.getId();
        String requestId = UUID.randomUUID().toString();
        CleanupCommandRequest body = new CleanupCommandRequest(taskId, document.getKbId(),
                document.getId().toString(), requestId);
        Integer status = webClient.post().uri(instance.getUri() + "/internal/v1/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Sage-Timestamp", Long.toString(timestamp))
                .header("X-Sage-Signature", sign(canonical(body, timestamp)))
                .bodyValue(body)
                .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                .block();
        if (status == null || status != 202) {
            throw new BusinessException(ErrorCode.CLEANUP_DISPATCH_FAILED, "RAG 清理命令派发失败");
        }
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

    private static String canonical(CleanupCommandRequest request, long timestamp) {
        return request.knowledgeBaseId() + ":" + request.documentId() + ":" + request.taskId() + ":"
                + request.requestId() + ":" + timestamp;
    }

    private record CleanupCommandRequest(String taskId, long knowledgeBaseId, String documentId,
            String requestId) { }
}
