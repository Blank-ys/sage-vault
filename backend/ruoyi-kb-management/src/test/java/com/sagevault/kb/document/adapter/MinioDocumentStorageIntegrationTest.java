package com.sagevault.kb.document.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.bootstrap.MinioProperties;
import com.sagevault.kb.document.service.port.DocumentStorage;
import com.sagevault.kb.platform.error.BusinessException;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MinioDocumentStorageIntegrationTest {
    private static final String ENDPOINT_ENV = "SAGE_VAULT_MINIO_ENDPOINT";
    private static final String ACCESS_KEY_ENV = "SAGE_VAULT_MINIO_ACCESS_KEY";
    private static final String SECRET_KEY_ENV = "SAGE_VAULT_MINIO_SECRET_KEY";

    private DocumentStorage storage;
    private MinioClient client;
    private String bucket;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv(ENDPOINT_ENV);
        String accessKey = System.getenv(ACCESS_KEY_ENV);
        String secretKey = System.getenv(SECRET_KEY_ENV);
        Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank()
                        && accessKey != null && !accessKey.isBlank()
                        && secretKey != null && !secretKey.isBlank(),
                "requires an explicitly configured MinIO instance");
        bucket = "sage-vault-test-" + UUID.randomUUID();
        MinioProperties properties = new MinioProperties(endpoint, accessKey, secretKey, bucket);
        MinioDocumentStorage minioStorage = new MinioDocumentStorage(properties);
        minioStorage.initialize();
        storage = minioStorage;
        client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Test
    void storesTxtObjectAndMakesItRetrievable() throws Exception {
        String objectKey = "documents/7/" + UUID.randomUUID() + "/test.txt";
        byte[] content = "企业文档内容".getBytes(StandardCharsets.UTF_8);

        storage.save(objectKey, new ByteArrayInputStream(content), content.length, "text/plain");

        var stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(content.length);
        assertThat(stat.contentType()).startsWith("text/plain");
    }

    @Test
    void failsForUnknownBucketWhenCredentialsAreInvalid() {
        MinioProperties badProperties = new MinioProperties(
                "http://192.0.2.1:9000", "invalid", "invalid", "unreachable-bucket");
        MinioDocumentStorage badStorage = new MinioDocumentStorage(badProperties);

        assertThatThrownBy(badStorage::initialize)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文档存储服务初始化失败");
    }
}
