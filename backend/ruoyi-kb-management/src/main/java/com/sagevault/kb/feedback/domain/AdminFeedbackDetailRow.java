package com.sagevault.kb.feedback.domain;

import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 反馈与其问答记录联查后的持久化行。
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

    private String requestId;

    private String question;

    private String answer;

    private QaRecordStatus answerStatus;
}
