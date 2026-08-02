package com.sagevault.kb.knowledgebase.domain;

public class KnowledgeBaseEntity {
    private Long id;
    private String name;
    private String normalizedName;
    private String description;
    private KnowledgeBaseStatus status;
    private String errorMessage;
    private Integer cleanupAttempt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public KnowledgeBaseStatus getStatus() { return status; }
    public void setStatus(KnowledgeBaseStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getCleanupAttempt() { return cleanupAttempt; }
    public void setCleanupAttempt(Integer cleanupAttempt) { this.cleanupAttempt = cleanupAttempt; }
}
