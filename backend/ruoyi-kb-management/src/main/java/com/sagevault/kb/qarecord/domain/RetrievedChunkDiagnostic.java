package com.sagevault.kb.qarecord.domain;

/**
 * 单个召回片段的诊断信息。
 *
 * <p>只承载片段标识与检索分数，不含片段正文：技术诊断不需要正文，
 * 复制正文会把企业文档内容扩散到管理端与日志。
 *
 * <p>数据来源由 11c 贯通：Python completed 事件携带，经 RAG 适配器解析后落库
 * {@code sv_qa_retrieval_diagnostic} 子表，最终在管理端反馈详情中展示。
 */
public record RetrievedChunkDiagnostic(String documentId, String chunkId, Double score) {}
