package com.sagevault.kb.feedback.mapper;

import com.sagevault.kb.feedback.domain.AdminFeedbackDetailRow;
import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FeedbackMapper {
    int insert(FeedbackEntity entity);

    FeedbackEntity findByQaId(@Param("qaId") long qaId);

    List<FeedbackEntity> findByQaIds(@Param("qaIds") List<Long> qaIds);

    /** 管理端队列分页查询，按提交时间倒序。 */
    List<FeedbackEntity> findForAdmin(@Param("query") AdminFeedbackQuery query);

    /** 管理端队列总数，与 {@link #findForAdmin} 使用同一过滤条件。 */
    long countForAdmin(@Param("query") AdminFeedbackQuery query);

    /**
     * 按反馈 ID 联查问答正文。
     *
     * <p>用 INNER JOIN 从反馈表出发，未提交反馈的问答在查询层就不可达，
     * 隐私边界由 SQL 保证而不是依赖调用方过滤。
     */
    AdminFeedbackDetailRow findDetailForAdmin(@Param("id") long id);

    /** 更新处理状态与内部备注，返回受影响行数用于判断反馈是否存在。 */
    int updateStatus(
            @Param("id") long id,
            @Param("status") FeedbackStatus status,
            @Param("adminNote") String adminNote);
}
