package com.sagevault.kb.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 残留检测 FAILSAFE 的调度触发入口。
 *
 * <p>只持有扫描节奏与阈值策略；具体的残留检测、终态裁决与幂等 CAS 由
 * {@link DocumentRecordWriter#failStuckCleaning(int)} 拥有，scheduler 不直接接触 Mapper。
 */
@Component
public class AutoCleanupTask {
    private static final Logger log = LoggerFactory.getLogger(AutoCleanupTask.class);

    /** 清理尝试次数阈值：达到该值仍未完成则判定为残留。 */
    public static final int FAILSAFE_ATTEMPT_THRESHOLD = 5;

    private final DocumentRecordWriter recordWriter;

    public AutoCleanupTask(DocumentRecordWriter recordWriter) {
        this.recordWriter = recordWriter;
    }

    @Scheduled(fixedDelay = 30000)
    public void detectStuckCleaning() {
        int marked = recordWriter.failStuckCleaning(FAILSAFE_ATTEMPT_THRESHOLD);
        if (marked > 0) {
            log.warn("Auto-cleanup FAILSAFE marked {} stuck DELETING document(s) as CLEANUP_FAILED", marked);
        }
    }
}
