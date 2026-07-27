package com.sagevault.kb.document.service.port;

import java.io.InputStream;

public interface DocumentStorage {
    void save(String objectKey, InputStream content, long size, String contentType);
}
