package com.sagevault.kb.feedback.mapper;

import com.sagevault.kb.feedback.domain.FeedbackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FeedbackMapper {
    int insert(FeedbackEntity entity);
    FeedbackEntity findByQaId(@Param("qaId") long qaId);
    List<FeedbackEntity> findByQaIds(@Param("qaIds") List<Long> qaIds);
}
