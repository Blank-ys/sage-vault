package com.sagevault.kb.knowledgebase.service.port;

/**
 * 知识库级联清理的窄接口：知识库能力只表达"清理这个知识库的全部内容"，
 * 不感知文档表、MinIO 对象或 Milvus 集合等实现细节。
 */
public interface KnowledgeBaseContentCleaner {

    /**
     * 级联清理的失败阶段。
     *
     * <p>知识管理员看到"删除失败"时必须能判断卡在哪一环节，才能决定是重试还是先修外部依赖，
     * 因此阶段是与失败原因同等重要的诊断信息，不能只留一段自由文本。
     */
    enum FailureStage {
        /** 向量清理失败：Milvus 侧未确认删除。 */
        VECTOR("向量清理"),
        /** 原文件清理失败：MinIO 对象未确认删除。 */
        OBJECT("原文件清理"),
        /** 文档记录清理失败：外部资源已删但业务记录未能移除。 */
        RECORD("文档记录清理"),
        /** 阶段无法从下游诊断信息中判定。 */
        UNKNOWN("未知阶段");

        private final String desc;

        FailureStage(String desc) { this.desc = desc; }

        public String getDesc() { return desc; }

        /** 把下游上报的阶段标识映射为知识库级别的失败阶段，无法识别时归入 UNKNOWN。 */
        public static FailureStage from(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            for (FailureStage stage : values()) {
                if (normalized.contains(stage.name())) {
                    return stage;
                }
            }
            return UNKNOWN;
        }
    }

    /**
     * 单轮级联清理的结果。
     *
     * @param finished 内容已全部清理干净，知识库活动记录可以移除
     * @param remaining 仍待清理的文档数量
     * @param failureStage 失败发生在哪一环节，null 表示本轮没有失败
     * @param failureMessage 无法自动恢复的失败原因，null 表示本轮没有失败
     */
    record CleanupProgress(boolean finished, int remaining, FailureStage failureStage, String failureMessage) {

        /** 全部内容已清理干净，知识库活动记录可以移除。 */
        public static CleanupProgress completed() {
            return new CleanupProgress(true, 0, null, null);
        }

        /** 仍有内容在清理中，下一轮继续推进。 */
        public static CleanupProgress inProgress(int remaining) {
            return new CleanupProgress(false, remaining, null, null);
        }

        /** 存在无法自动恢复的清理失败，知识库需进入 DELETE_FAILED 并保留阶段与诊断信息。 */
        public static CleanupProgress failed(FailureStage stage, String failureMessage) {
            return new CleanupProgress(false, 0, stage == null ? FailureStage.UNKNOWN : stage, failureMessage);
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

    /**
     * 管理员重试删除时，让上一轮已判定失败的内容重新进入清理流程。
     *
     * <p>没有这一步，重试会立刻读到上次的失败残留并再次判失败，"重试"永远不可能成功。
     * 实现必须幂等：已经清理干净的内容不得被再次删除。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 重新纳入清理的内容数量
     */
    int retryFailedContent(long knowledgeBaseId);
}
