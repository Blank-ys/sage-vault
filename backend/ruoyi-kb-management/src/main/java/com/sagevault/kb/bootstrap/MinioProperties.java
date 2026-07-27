package com.sagevault.kb.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sage-vault.minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
}
