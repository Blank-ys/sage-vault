package com.sagevault.kb.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.domain.UpdateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.CleanupProgress;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.FailureStage;
import com.sagevault.kb.platform.error.BusinessException;
import com.sagevault.kb.support.InMemoryRepositories;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 知识库级联删除的能力级验证：删除请求关闭新写入，后台清理确认内容清空后才移除活动记录，
 * 清理失败时保留残留并暴露诊断信息。
 */
class KnowledgeBaseCascadeDeleteTest {
    private InMemoryRepositories repositories;
    private KnowledgeBaseService knowledgeBases;
    private long knowledgeBaseId;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        knowledgeBases = repositories.knowledgeBases();
        knowledgeBaseId = knowledgeBases.create(new CreateKnowledgeBaseRequest("产品手册", "级联删除验证")).id();
    }

    @Test
    void deleteMovesKnowledgeBaseToDeletingAndImmediatelyRejectsNewWrites() {
        assertThat(knowledgeBases.delete(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);

        // 上传、建会话与提问共用 requireAvailable 这一道闸门，删除请求返回即已关闭
        assertThatThrownBy(() -> knowledgeBases.requireAvailable(knowledgeBaseId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可用");

        // 删除中的知识库不出现在可选知识库列表里
        assertThat(knowledgeBases.listAvailable()).extracting(KnowledgeBaseResponse::id)
                .doesNotContain(knowledgeBaseId);
    }

    @Test
    void repeatedDeleteIsIdempotentAndDoesNotRedispatchCleanup() {
        knowledgeBases.delete(knowledgeBaseId);

        assertThat(knowledgeBases.delete(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);
        assertThat(knowledgeBases.get(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);
    }

    @Test
    void activityRecordIsRemovedOnlyAfterContentCleanupIsConfirmed() {
        knowledgeBases.delete(knowledgeBaseId);
        RecordingCleaner cleaner = new RecordingCleaner(
                CleanupProgress.inProgress(2), CleanupProgress.inProgress(1), CleanupProgress.completed());
        KnowledgeBaseCascadeDeleteTask task = repositories.cascadeDeleteTask(cleaner);

        // 内容尚未清空前，知识库活动记录必须保留
        task.advanceCascadeDeletes();
        assertThat(knowledgeBases.get(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);
        task.advanceCascadeDeletes();
        assertThat(knowledgeBases.get(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);

        // 内容确认清空后活动记录被移除，知识库不再可查
        task.advanceCascadeDeletes();
        assertThatThrownBy(() -> knowledgeBases.get(knowledgeBaseId)).isInstanceOf(BusinessException.class);
        assertThat(cleaner.calls).isEqualTo(3);
    }

    @Test
    void deletedKnowledgeBaseRejectsNewWritesWithDeletedSignal() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.completed())).advanceCascadeDeletes();

        assertThatThrownBy(() -> knowledgeBases.requireAvailable(knowledgeBaseId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库已删除");
    }

    @Test
    void cleanupFailureKeepsResidueVisibleInsteadOfFakingSuccess() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(
                        new RecordingCleaner(CleanupProgress.failed(FailureStage.VECTOR, "Milvus 集合删除失败")))
                .advanceCascadeDeletes();

        KnowledgeBaseResponse failed = knowledgeBases.get(knowledgeBaseId);
        assertThat(failed.status()).isEqualTo(KnowledgeBaseStatus.DELETE_FAILED);
        assertThat(failed.errorMessage()).contains("Milvus 集合删除失败");
    }

    /** 知识管理员必须能分辨卡在哪一环节，才能判断是直接重试还是先修外部依赖。 */
    @Test
    void cleanupFailureReportsWhichStageFailed() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(
                        new RecordingCleaner(CleanupProgress.failed(FailureStage.OBJECT, "MinIO 拒绝删除对象")))
                .advanceCascadeDeletes();

        assertThat(knowledgeBases.get(knowledgeBaseId).errorMessage())
                .contains("原文件清理")
                .contains("MinIO 拒绝删除对象");
    }

    /** 删除流程中的知识库不得被编辑：改名会让一个正在消失的知识库看起来重新可用。 */
    @Test
    void knowledgeBaseInDeleteFlowRejectsEditing() {
        knowledgeBases.delete(knowledgeBaseId);
        assertThatThrownBy(() -> knowledgeBases.update(knowledgeBaseId,
                new UpdateKnowledgeBaseRequest("改名", "删除中不该被编辑")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持查看与重试删除");

        repositories.cascadeDeleteTask(
                        new RecordingCleaner(CleanupProgress.failed(FailureStage.RECORD, "文档记录删除失败")))
                .advanceCascadeDeletes();

        assertThatThrownBy(() -> knowledgeBases.update(knowledgeBaseId,
                new UpdateKnowledgeBaseRequest("改名", "失败态也不该被编辑")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅支持查看与重试删除");
        // 失败态仍可查看，且不会被误判为可用
        assertThat(knowledgeBases.get(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETE_FAILED);
        assertThatThrownBy(() -> knowledgeBases.requireAvailable(knowledgeBaseId))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * FAILSAFE 耗尽后的重试必须拿到全新的清理预算。
     *
     * <p>若重试不归零 cleanup_attempt，第一轮就会再次越过阈值，"重试"永远不可能成功。
     */
    @Test
    void retryAfterFailsafeExhaustionGetsFreshCleanupBudget() {
        knowledgeBases.delete(knowledgeBaseId);
        // 反复停在"仍有残留"，直到 FAILSAFE 判定失败
        KnowledgeBaseCascadeDeleteTask stuck =
                repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.inProgress(1)));
        for (int i = 0; i < 25; i++) {
            stuck.advanceCascadeDeletes();
        }
        assertThat(knowledgeBases.get(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETE_FAILED);

        knowledgeBases.delete(knowledgeBaseId);
        assertThat(repositories.knowledgeBaseMapper().findById(knowledgeBaseId).getCleanupAttempt()).isZero();
        // 重试必须把上一轮失败的内容重新拉回清理流程
        assertThat(repositories.retriedContentCleanups()).contains(knowledgeBaseId);

        repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.completed())).advanceCascadeDeletes();
        assertThatThrownBy(() -> knowledgeBases.get(knowledgeBaseId)).isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteCanBeRetriedAfterCleanupFailure() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(
                        new RecordingCleaner(CleanupProgress.failed(FailureStage.OBJECT, "MinIO 原文件删除失败")))
                .advanceCascadeDeletes();

        assertThat(knowledgeBases.delete(knowledgeBaseId).status()).isEqualTo(KnowledgeBaseStatus.DELETING);
        repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.completed())).advanceCascadeDeletes();
        assertThatThrownBy(() -> knowledgeBases.get(knowledgeBaseId)).isInstanceOf(BusinessException.class);
    }

    /** 按脚本返回清理进度，并记录被推进的轮数。 */
    private static final class RecordingCleaner implements KnowledgeBaseContentCleaner {
        private final List<CleanupProgress> script;
        private int calls;

        private RecordingCleaner(CleanupProgress... script) {
            this.script = new ArrayList<>(List.of(script));
        }

        @Override
        public CleanupProgress cleanupContent(long knowledgeBaseId) {
            CleanupProgress progress = script.get(Math.min(calls, script.size() - 1));
            calls++;
            return progress;
        }

        @Override
        public int retryFailedContent(long knowledgeBaseId) {
            return 0;
        }
    }
}
