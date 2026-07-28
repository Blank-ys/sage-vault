package com.sagevault.kb.document.mapper;

import com.sagevault.kb.document.domain.IndexingTaskEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

public interface IndexingTaskMapper {
    int insert(IndexingTaskEntity entity);

    IndexingTaskEntity findByTaskId(@Param("taskId") String taskId);

    int updateTerminalState(@Param("taskId") String taskId, @Param("attempt") int attempt,
            @Param("status") String status, @Param("errorMessage") String errorMessage,
            @Param("callbackReceivedAt") LocalDateTime callbackReceivedAt);
}
