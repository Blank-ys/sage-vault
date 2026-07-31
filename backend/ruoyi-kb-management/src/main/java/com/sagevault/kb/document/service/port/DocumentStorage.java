package com.sagevault.kb.document.service.port;

import java.io.InputStream;
import java.time.Duration;

public interface DocumentStorage {
    void save(String objectKey, InputStream content, long size, String contentType);

    String presignedUrl(String objectKey, Duration expiry);

    void deleteByPrefix(String prefix);
}
