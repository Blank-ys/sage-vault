package com.sagevault.kb.document.controller;

import com.sagevault.kb.bootstrap.RagProperties;
import com.sagevault.kb.document.domain.CleanupCallbackRequest;
import com.sagevault.kb.document.service.CleanupCallbackHandler;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/cleanup/callbacks")
public class CleanupCallbackController {
    private static final Logger log = LoggerFactory.getLogger(CleanupCallbackController.class);
    private static final long REPLAY_WINDOW_SECONDS = 60;

    private final CleanupCallbackHandler handler;
    private final RagProperties properties;
    private final ConcurrentHashMap<String, Long> seenRequests = new ConcurrentHashMap<>();

    public CleanupCallbackController(CleanupCallbackHandler handler, RagProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @PostMapping
    public void callback(
            @RequestBody CleanupCallbackRequest request,
            @RequestHeader("X-Sage-Timestamp") String timestampHeader,
            @RequestHeader("X-Sage-Signature") String signature) {
        verifySignature(request, timestampHeader, signature);
        handler.handle(request);
    }

    private void verifySignature(CleanupCallbackRequest request, String timestampHeader, String signature) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.CLEANUP_CALLBACK_INVALID, "回调签名无效");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > REPLAY_WINDOW_SECONDS) {
            throw new BusinessException(ErrorCode.CLEANUP_CALLBACK_INVALID, "回调签名无效");
        }
        String replayKey = request.requestId() + ":" + request.taskId();
        long now = Instant.now().getEpochSecond();
        seenRequests.entrySet().removeIf(entry -> entry.getValue() < now);
        if (seenRequests.putIfAbsent(replayKey, now + REPLAY_WINDOW_SECONDS) != null) {
            throw new BusinessException(ErrorCode.CLEANUP_CALLBACK_INVALID, "回调签名无效");
        }
        String expected = sign(canonical(request, timestamp));
        if (!constantTimeEquals(expected, signature)) {
            throw new BusinessException(ErrorCode.CLEANUP_CALLBACK_INVALID, "回调签名无效");
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

    private static String canonical(CleanupCallbackRequest request, long timestamp) {
        return request.taskId() + ":" + request.documentId() + ":" + request.success() + ":"
                + request.requestId() + ":" + timestamp;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
