package com.sagevault.kb.feedback.domain;

/**
 * 管理端反馈队列的查询条件。
 *
 * <p>{@code status} 为空表示不按状态过滤。分页参数在构造时做规范化，
 * 避免非法页码或超大页尺寸传到 SQL 层。
 */
public record AdminFeedbackQuery(FeedbackStatus status, int pageNum, int pageSize) {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    public static AdminFeedbackQuery of(FeedbackStatus status, Integer pageNum, Integer pageSize) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize;
        return new AdminFeedbackQuery(
                status, normalizedPageNum, Math.min(normalizedPageSize, MAX_PAGE_SIZE));
    }

    public int offset() {
        return (pageNum - 1) * pageSize;
    }
}
