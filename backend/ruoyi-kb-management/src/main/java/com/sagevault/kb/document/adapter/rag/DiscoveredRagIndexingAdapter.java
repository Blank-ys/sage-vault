package com.sagevault.kb.document.adapter.rag;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.service.port.DocumentStorage;
import com.sagevault.kb.document.service.port.IndexingCommandDispatcher;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
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
public class DiscoveredRagIndexingAdapter implements IndexingCommandDispatcher {
    private static final Duration SOURCE_URL_EXPIRY = Duration.ofMinutes(10);

    private final DiscoveryClient discovery;
    private final WebClient webClient;
    private final RagProperties properties;
    private final DocumentStorage storage;

    public DiscoveredRagIndexingAdapter(DiscoveryClient discovery, WebClient.Builder webClient,
            RagProperties properties, DocumentStorage storage) {
        this.discovery = discovery;
        this.webClient = webClient.build();
        this.properties = properties;
        this.storage = storage;
    }

    @Override
    public void dispatch(DocumentEntity document, IndexingTaskEntity task) {
        ServiceInstance instance = discovery.getInstances(properties.serviceId()).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.RAG_UNAVAILABLE, "RAG 服务暂不可用"));
        long timestamp = Instant.now().getEpochSecond();
        String requestId = UUID.randomUUID().toString();
        String sourceUrl = storage.presignedUrl(document.getObjectKey(), SOURCE_URL_EXPIRY);
        IndexingCommandRequest body = new IndexingCommandRequest(task.getTaskId(), task.getAttempt(),
                document.getKbId(), document.getId().toString(), document.getFilename(), sourceUrl, requestId);
        Integer status = webClient.post().uri(instance.getUri() + "/internal/v1/indexing")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Sage-Timestamp", Long.toString(timestamp))
                .header("X-Sage-Signature", sign(canonical(body, timestamp)))
                .bodyValue(body)
                .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                .block();
        if (status == null || status != 202) {
            throw new BusinessException(ErrorCode.INDEXING_DISPATCH_FAILED, "RAG 入库命令派发失败");
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

    private static String canonical(IndexingCommandRequest request, long timestamp) {
        return request.knowledgeBaseId() + ":" + request.documentId() + ":" + request.taskId() + ":"
                + request.attempt() + ":" + request.requestId() + ":" + timestamp;
    }

    private record IndexingCommandRequest(String taskId, int attempt, long knowledgeBaseId, String documentId,
            String filename, String sourceUrl, String requestId) { }
}
