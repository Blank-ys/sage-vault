package com.sagevault.kb.qarecord.domain;

import java.time.LocalDateTime;

/**
 * 单次回答的检索/生成阶段诊断，落于 {@code sv_qa_retrieval_diagnostic} 子表。
 *
 * <p>只承载片段标识、分数与阶段耗时，不含片段正文：技术诊断不需要正文，
 * 复制正文会把企业文档内容扩散到管理端与日志。一次回答可产生多条片段诊断记录。
 */
public class RetrievalDiagnosticEntity {
    private Long id;
    private Long qaRecordId;
    private String generationId;
    private String documentId;
    private String chunkId;
    private Double score;
    private String stage;
    private Long durationMs;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQaRecordId() { return qaRecordId; }
    public void setQaRecordId(Long qaRecordId) { this.qaRecordId = qaRecordId; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
