package com.sagevault.kb.knowledgebase.service;

import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.CleanupProgress;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.FailureStage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库级联删除的后台推进器。
 *
 * <p>删除接口只负责让知识库进入 DELETING 并立即关闭新写入，真正的内容清理在这里分轮推进：
 * 每轮调用 {@link KnowledgeBaseContentCleaner} 清理文档、原文件与向量，只有在内容确认清空后
 * 才移除知识库活动记录。清理失败时置为 {@link KnowledgeBaseStatus#DELETE_FAILED} 并保留残留，
 * 不伪装成删除成功。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseCascadeDeleteTask {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseCascadeDeleteTask.class);

    /** 清理轮次上限：达到该值仍未清空则判定为残留，交由知识管理员重试。 */
    public static final int FAILSAFE_ATTEMPT_THRESHOLD = 20;

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeBaseContentCleaner contentCleaner;

    @Scheduled(fixedDelay = 5000)
    public void advanceCascadeDeletes() {
        List<KnowledgeBaseEntity> deleting = mapper.findByStatus(KnowledgeBaseStatus.DELETING.name());
        for (KnowledgeBaseEntity knowledgeBase : deleting) {
            try {
                advance(knowledgeBase);
            } catch (RuntimeException exception) {
                log.error("Cascade delete round failed for knowledge base {}", knowledgeBase.getId(), exception);
            }
        }
    }

    private void advance(KnowledgeBaseEntity knowledgeBase) {
        long id = knowledgeBase.getId();
        CleanupProgress progress = contentCleaner.cleanupContent(id);

        if (progress.isFailed()) {
            markFailed(id, progress.failureStage(), progress.failureMessage());
            return;
        }

        if (progress.finished()) {
            // 与并发上传的配合：这里只在状态仍为 DELETING 时移除活动记录，
            // 而上传在插入文档后会复检知识库可用性，读到"已删除"即回滚。
            // 两侧各守一半，插入与扫描的交错顺序都不会留下孤儿文档。
            int removed = mapper.deleteByIdIfDeleting(id);
            if (removed > 0) {
                log.info("Knowledge base {} cascade delete completed, activity record removed", id);
            }
            return;
        }

        mapper.incrementCleanupAttempt(id);
        int attempt = knowledgeBase.getCleanupAttempt() == null ? 0 : knowledgeBase.getCleanupAttempt();
        if (attempt + 1 >= FAILSAFE_ATTEMPT_THRESHOLD) {
            markFailed(id, FailureStage.UNKNOWN, "FAILSAFE：清理在 " + FAILSAFE_ATTEMPT_THRESHOLD + " 轮后仍有 "
                    + progress.remaining() + " 个文档未清理完成");
        }
    }

    /**
     * 置为 DELETE_FAILED，并把失败阶段与原因一起落库。
     *
     * <p>阶段前缀让知识管理员不用翻日志就能判断卡在向量、原文件还是文档记录，
     * 决定"直接重试"还是"先修外部依赖再重试"。
     */
    private void markFailed(long id, FailureStage stage, String message) {
        FailureStage effective = stage == null ? FailureStage.UNKNOWN : stage;
        String reason = "[" + effective.getDesc() + "] " + message;
        int updated = mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETE_FAILED.name(),
                truncate(reason), KnowledgeBaseStatus.DELETING.name());
        if (updated > 0) {
            log.error("Knowledge base {} cascade delete failed at stage {}: {}", id, effective, message);
        }
    }

    /** 失败原因落库前截断到列宽，避免诊断信息写入失败反而丢掉整条记录。 */
    private static String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
