package com.sagevault.kb.document.service.impl;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import com.sagevault.kb.document.service.port.CleanupCommandDispatcher;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识库级联清理在文档侧的实现。
 *
 * <p>文档能力拥有文档记录、MinIO 原文件与向量清理命令，因此由它实现知识库暴露的
 * {@link KnowledgeBaseContentCleaner} 窄接口。每轮推进复用 issue 06 的单文档清理链路：
 * 把尚未进入清理的文档置为 DELETING 并派发清理命令，已在清理中的文档等待 Python 回调
 * 删除记录。文档记录清空即代表原文件与向量都已确认删除。
 */
@Component
@RequiredArgsConstructor
public class DocumentKnowledgeBaseContentCleaner implements KnowledgeBaseContentCleaner {
    private static final Logger log = LoggerFactory.getLogger(DocumentKnowledgeBaseContentCleaner.class);

    private final DocumentMapper documentMapper;
    private final CleanupCommandDispatcher cleanupDispatcher;

    @Override
    public CleanupProgress cleanupContent(long knowledgeBaseId) {
        List<DocumentEntity> documents = documentMapper.findByKbId(knowledgeBaseId);
        if (documents.isEmpty()) {
            // 文档记录已全部移除，等价于原文件与向量都已确认清理
            return CleanupProgress.completed();
        }

        List<DocumentEntity> cleanupFailed = documents.stream()
                .filter(document -> document.getStatus() == DocumentStatus.CLEANUP_FAILED)
                .toList();
        if (!cleanupFailed.isEmpty()) {
            // 存在无法自动恢复的清理失败：保留残留并向知识管理员暴露诊断信息
            return CleanupProgress.failed(buildFailureMessage(cleanupFailed));
        }

        for (DocumentEntity document : documents) {
            if (document.getStatus() != DocumentStatus.DELETING) {
                beginCleanup(document);
            }
        }
        return CleanupProgress.inProgress(documents.size());
    }

    /** 把单个文档推进到 DELETING 并派发清理命令；派发失败留待下一轮重试。 */
    private void beginCleanup(DocumentEntity document) {
        int updated = documentMapper.updateStatusIfCurrentStatus(document.getId(),
                DocumentStatus.DELETING.name(), "", document.getStatus().name());
        if (updated == 0) {
            // 状态已被其他流程推进，下一轮重新读取最新状态
            return;
        }
        document.setStatus(DocumentStatus.DELETING);
        try {
            cleanupDispatcher.dispatch(document);
        } catch (RuntimeException exception) {
            log.warn("Cascade cleanup dispatch failed for document {}, will retry next round",
                    document.getId(), exception);
        }
    }

    private static String buildFailureMessage(List<DocumentEntity> cleanupFailed) {
        DocumentEntity first = cleanupFailed.get(0);
        String detail = first.getErrorMessage() == null || first.getErrorMessage().isBlank()
                ? "无详细诊断信息"
                : first.getErrorMessage();
        return "存在 " + cleanupFailed.size() + " 个文档清理失败，例如《" + first.getFilename() + "》：" + detail;
    }
}
