package com.sagevault.kb.document.adapter;

import com.sagevault.kb.bootstrap.MinioProperties;
import com.sagevault.kb.document.service.port.DocumentStorage;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MinioDocumentStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(MinioDocumentStorage.class);

    private final MinioProperties properties;
    private MinioClient client;

    public MinioDocumentStorage(MinioProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
        ensureBucketExists();
    }

    @Override
    public void save(String objectKey, InputStream content, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException exception) {
            log.error("Failed to store document object {}", objectKey, exception);
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "文档原文件存储失败");
        }
    }

    @Override
    public String presignedUrl(String objectKey, Duration expiry) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) expiry.getSeconds())
                    .build());
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException exception) {
            log.error("Failed to generate presigned URL for object {}", objectKey, exception);
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "无法生成文档下载地址");
        }
    }

    @Override
    public void deleteByPrefix(String prefix) {
        try {
            Iterable<Result<Item>> objects = client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : objects) {
                String objectName = result.get().objectName();
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.bucket())
                        .object(objectName)
                        .build());
            }
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException exception) {
            log.error("Failed to delete objects with prefix {}", prefix, exception);
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "文档存储清理失败");
        }
    }

    private void ensureBucketExists() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            }
        } catch (MinioException | IOException | InvalidKeyException | NoSuchAlgorithmException exception) {
            log.error("Failed to initialize MinIO bucket {}", properties.bucket(), exception);
            throw new BusinessException(ErrorCode.DOCUMENT_STORAGE_FAILED, "文档存储服务初始化失败");
        }
    }
}
