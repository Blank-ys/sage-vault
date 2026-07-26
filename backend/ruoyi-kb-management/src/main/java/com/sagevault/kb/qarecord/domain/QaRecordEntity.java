package com.sagevault.kb.qarecord.domain;

public class QaRecordEntity {
    private Long id;
    private Long conversationId;
    private Long userId;
    private String requestId;
    private String generationId;
    private String question;
    private String answer;
    private QaRecordStatus status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getGenerationId() { return generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public QaRecordStatus getStatus() { return status; }
    public void setStatus(QaRecordStatus status) { this.status = status; }
}
