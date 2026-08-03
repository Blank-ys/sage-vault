package com.sagevault.kb.qarecord.mapper;

import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 检索/生成阶段诊断子表的访问入口。 */
public interface RetrievalDiagnosticMapper {
    /** 批量写入一次回答的片段诊断，单条 SQL 完成，避免循环单插。 */
    int insertBatch(@Param("items") List<RetrievalDiagnosticEntity> items);

    /** 按问答记录 id 返回其全部诊断（片段诊断 + 阶段耗时聚合前原始行）。 */
    List<RetrievalDiagnosticEntity> findByQaRecordId(@Param("qaRecordId") long qaRecordId);

    /** 删除会话级联清理时清除其全部诊断。 */
    int deleteByConversation(@Param("conversationId") long conversationId);
}
