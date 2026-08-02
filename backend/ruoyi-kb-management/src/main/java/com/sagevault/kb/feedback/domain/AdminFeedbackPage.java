package com.sagevault.kb.feedback.domain;

import java.util.List;

/** 管理端反馈队列的分页结果。 */
public record AdminFeedbackPage(
        List<AdminFeedbackSummary> items, long total, int pageNum, int pageSize) {}
