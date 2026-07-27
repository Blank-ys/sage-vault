package com.sagevault.kb.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sage-vault.rag")
public record RagProperties(String serviceId, String signingKey) {
}
