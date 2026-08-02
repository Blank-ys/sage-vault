package com.sagevault.kb.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseResponse;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner.CleanupProgress;
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
        repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.failed("Milvus 集合删除失败")))
                .advanceCascadeDeletes();

        KnowledgeBaseResponse failed = knowledgeBases.get(knowledgeBaseId);
        assertThat(failed.status()).isEqualTo(KnowledgeBaseStatus.DELETE_FAILED);
        assertThat(failed.errorMessage()).contains("Milvus 集合删除失败");
    }

    @Test
    void deleteCanBeRetriedAfterCleanupFailure() {
        knowledgeBases.delete(knowledgeBaseId);
        repositories.cascadeDeleteTask(new RecordingCleaner(CleanupProgress.failed("MinIO 原文件删除失败")))
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
    }
}
