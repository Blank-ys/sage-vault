package com.sagevault.kb.qarecord.service;

import com.sagevault.kb.qarecord.domain.QaRecordEvidence;
import java.util.Optional;

/**
 * 问答记录的跨能力只读 seam。
 *
 * <p>只读接口，返回不可变的证据快照而不是持久化对象；检索与阶段诊断在实现内组装。
 * 调用方必须在已取得正文授权（例如已存在对应反馈行）后才调用，未授权不得读取正文。
 */
public interface QaRecordEvidenceService {
    /**
     * 返回一次问答的证据快照（正文 + 状态 + 检索/阶段诊断）。
     *
     * @param qaId 问答记录 id
     * @return 快照；记录不存在时返回 empty
     */
    Optional<QaRecordEvidence> findEvidence(long qaId);
}
