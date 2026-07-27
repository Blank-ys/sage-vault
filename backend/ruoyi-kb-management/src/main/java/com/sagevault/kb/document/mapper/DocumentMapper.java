package com.sagevault.kb.document.mapper;

import com.sagevault.kb.document.domain.DocumentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DocumentMapper {
    int insert(DocumentEntity entity);

    int updateStatus(@Param("id") long id, @Param("status") String status,
            @Param("errorMessage") String errorMessage);

    DocumentEntity findById(long id);

    DocumentEntity findByKbIdAndNormalizedName(@Param("kbId") long kbId,
            @Param("normalizedName") String normalizedName);

    List<DocumentEntity> findByKbId(long kbId);
}
