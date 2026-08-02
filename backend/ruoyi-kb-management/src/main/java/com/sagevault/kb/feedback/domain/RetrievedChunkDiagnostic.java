package com.sagevault.kb.feedback.domain;

/**
 * 单个召回片段的诊断信息。
 *
 * <p>只承载片段标识与检索分数，不含片段正文：技术诊断不需要正文，
 * 复制正文会把企业文档内容扩散到管理端与日志。
 *
 * <p>数据来源由 11c 建立，当前恒为空集合。
 */
public record RetrievedChunkDiagnostic(String documentId, String chunkId, Double score) {}
