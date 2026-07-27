package com.sagevault.kb.document.domain;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.Locale;

/** Canonical enterprise-document filename used for display and uniqueness checks within a knowledge base. */
public record DocumentFilename(String value, String normalizedValue, String extension) {

    public static DocumentFilename of(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "文档名称不能为空");
        }
        String trimmed = value.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == trimmed.length() - 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "文档名称必须包含扩展名");
        }
        String extension = trimmed.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (!"txt".equals(extension)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "仅支持上传 TXT 文件");
        }
        String nameWithoutExtension = trimmed.substring(0, lastDot);
        String normalized = nameWithoutExtension.toLowerCase(Locale.ROOT) + "." + extension;
        return new DocumentFilename(trimmed, normalized, extension);
    }
}
