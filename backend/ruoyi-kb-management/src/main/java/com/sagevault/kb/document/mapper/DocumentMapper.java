package com.sagevault.kb.document.mapper;

import com.sagevault.kb.document.domain.DocumentEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DocumentMapper {
    int insert(DocumentEntity entity);

    int updateStatus(@Param("id") long id, @Param("status") String status,
            @Param("errorMessage") String errorMessage);

    int updateStatusIfCurrentStatus(@Param("id") long id, @Param("newStatus") String newStatus,
            @Param("errorMessage") String errorMessage, @Param("currentStatus") String currentStatus);

    DocumentEntity findById(long id);

    DocumentEntity findByKbIdAndNormalizedName(@Param("kbId") long kbId,
            @Param("normalizedName") String normalizedName);

    List<DocumentEntity> findByKbIdAndNormalizedNames(@Param("kbId") long kbId,
            @Param("normalizedNames") Collection<String> normalizedNames);

    List<DocumentEntity> findByKbId(long kbId);

    int countAvailableByKbId(@Param("kbId") long kbId);

    int deleteById(long id);
}
