package com.sagevault.kb.document.domain;

import org.springframework.web.multipart.MultipartFile;

public record UploadDocumentRequest(long knowledgeBaseId, MultipartFile file) {
}
