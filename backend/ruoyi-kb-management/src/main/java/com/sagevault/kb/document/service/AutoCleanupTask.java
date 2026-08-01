package com.sagevault.kb.document.service;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.mapper.DocumentMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 残留检测 FAILSAFE。
 *
 * <p>定时扫描已进入清理阶段、仍处于 DELETING 且清理尝试次数达到阈值的文档，
 * 将其置为 {@link DocumentStatus#CLEANUP_FAILED} 终态，避免清理命令永久丢失导致
 * 文档卡在 DELETING 且不可检索也不可删除。该组件不依赖外部清理端口的回调，
 * 仅作为最后的兜底保护。
 */
@Component
public class AutoCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(AutoCleanupTask.class);

    /** 清理尝试次数阈值：达到该值仍未完成则判定为残留。 */
    public static final int FAILSAFE_ATTEMPT_THRESHOLD = 5;

    private final DocumentMapper documentMapper;

    public AutoCleanupTask(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Scheduled(fixedDelay = 30000)
    public void detectStuckCleaning() {
        List<DocumentEntity> stuck = documentMapper.findStuckCleaningDocuments(FAILSAFE_ATTEMPT_THRESHOLD);
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("Auto-cleanup FAILSAFE detected {} stuck DELETING document(s)", stuck.size());
        for (DocumentEntity document : stuck) {
            String message = "FAILSAFE：清理在达到尝试阈值 " + FAILSAFE_ATTEMPT_THRESHOLD
                    + " 后仍停留在 DELETING（阶段=" + document.getCleanupPhase() + "）";
            int updated = documentMapper.markCleanupFailed(document.getId(), message);
            if (updated > 0) {
                log.warn("Auto-cleanup FAILSAFE marked document {} as CLEANUP_FAILED", document.getId());
            }
        }
    }
}
