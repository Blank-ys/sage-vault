package com.sagevault.kb.qarecord.mapper;

import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import org.apache.ibatis.annotations.Param;

public interface QaRecordMapper {
    int insert(QaRecordEntity entity);
    int updateTerminalState(@Param("generationId") String generationId,
            @Param("status") QaRecordStatus status, @Param("answer") String answer);
    QaRecordEntity findByGenerationId(String generationId);
}
