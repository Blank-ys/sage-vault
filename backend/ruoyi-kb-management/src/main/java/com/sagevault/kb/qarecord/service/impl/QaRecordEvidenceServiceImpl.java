package com.sagevault.kb.qarecord.service.impl;

import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordEvidence;
import com.sagevault.kb.qarecord.domain.RetrievalDiagnosticEntity;
import com.sagevault.kb.qarecord.domain.RetrievedChunkDiagnostic;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import com.sagevault.kb.qarecord.mapper.RetrievalDiagnosticMapper;
import com.sagevault.kb.qarecord.service.QaRecordEvidenceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class QaRecordEvidenceServiceImpl implements QaRecordEvidenceService {
    private final QaRecordMapper mapper;
    private final RetrievalDiagnosticMapper diagnostics;

    public QaRecordEvidenceServiceImpl(QaRecordMapper mapper, RetrievalDiagnosticMapper diagnostics) {
        this.mapper = mapper;
        this.diagnostics = diagnostics;
    }

    @Override
    public Optional<QaRecordEvidence> findEvidence(long qaId) {
        QaRecordEntity record = mapper.findById(qaId);
        if (record == null) {
            return Optional.empty();
        }
        List<RetrievedChunkDiagnostic> chunks = new ArrayList<>();
        Map<String, Long> stages = new LinkedHashMap<>();
        for (RetrievalDiagnosticEntity entity : diagnostics.findByQaRecordId(qaId)) {
            if ("retrieval".equals(entity.getStage())) {
                chunks.add(new RetrievedChunkDiagnostic(
                        entity.getDocumentId(), entity.getChunkId(), entity.getScore()));
            } else if (entity.getStage() != null && entity.getDurationMs() != null) {
                stages.put(entity.getStage(), entity.getDurationMs());
            }
        }
        return Optional.of(new QaRecordEvidence(
                record.getId(), record.getUserId(), record.getRequestId(), record.getQuestion(),
                record.getAnswer(), record.getStatus(), chunks, stages));
    }
}
