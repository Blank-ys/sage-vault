package com.sagevault.kb.feedback.domain;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 反馈与其对应问答关联后的持久化行。
 *
 * <p>只承载反馈自身字段；问答正文与状态经 qarecord 的证据读取 seam
 * {@code QaRecordEvidenceService} 取得，不在反馈持久化行中复制。
 *
 * <p>MyBatis 结果映射需要可变对象与无参构造，因此这里用可变类而不是 record；
 * 对外返回时统一转换为不可变的 {@link AdminFeedbackDetail}。
 */
@Data
public class AdminFeedbackDetailRow {

    private Long id;

    private Long qaId;

    private FeedbackCategory category;

    private String comment;

    private FeedbackStatus status;

    private String adminNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
