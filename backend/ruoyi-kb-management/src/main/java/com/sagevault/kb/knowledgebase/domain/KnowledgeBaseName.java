package com.sagevault.kb.knowledgebase.domain;

import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.platform.error.ErrorCode;
import java.util.Locale;

/** Canonical knowledge-base name used for display and uniqueness checks. */
public record KnowledgeBaseName(String value, String normalizedValue) {
    public static KnowledgeBaseName of(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "知识库名称不能为空");
        }
        String trimmed = value.trim();
        return new KnowledgeBaseName(trimmed, trimmed.toLowerCase(Locale.ROOT));
    }
}
