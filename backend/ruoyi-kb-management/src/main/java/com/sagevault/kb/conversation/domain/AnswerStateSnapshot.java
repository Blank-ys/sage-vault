package com.sagevault.kb.conversation.domain;

import com.sagevault.kb.qarecord.domain.QaRecordStatus;

public record AnswerStateSnapshot(String generationId, boolean ready, QaRecordStatus status, String answer) { }
