package com.sagevault.kb.knowledgebase.service.port;

/**
 * 知识库级联清理的窄接口：知识库能力只表达"清理这个知识库的全部内容"，
 * 不感知文档表、MinIO 对象或 Milvus 集合等实现细节。
 */
public interface KnowledgeBaseContentCleaner {

    /**
     * 单轮级联清理的结果。
     *
     * @param finished 内容已全部清理干净，知识库活动记录可以移除
     * @param remaining 仍待清理的文档数量
     * @param failureMessage 无法自动恢复的失败原因，null 表示本轮没有失败
     */
    record CleanupProgress(boolean finished, int remaining, String failureMessage) {

        /** 全部内容已清理干净，知识库活动记录可以移除。 */
        public static CleanupProgress completed() {
            return new CleanupProgress(true, 0, null);
        }

        /** 仍有内容在清理中，下一轮继续推进。 */
        public static CleanupProgress inProgress(int remaining) {
            return new CleanupProgress(false, remaining, null);
        }

        /** 存在无法自动恢复的清理失败，知识库需进入 DELETE_FAILED 并保留诊断信息。 */
        public static CleanupProgress failed(String failureMessage) {
            return new CleanupProgress(false, 0, failureMessage);
        }

        public boolean isFailed() {
            return failureMessage != null;
        }
    }

    /**
     * 推进一轮知识库内容清理，必须可重复调用且不重复删除已清理的内容。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 本轮推进后的清理进度
     */
    CleanupProgress cleanupContent(long knowledgeBaseId);
}
