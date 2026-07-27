package com.sagevault.kb.knowledgebase.domain;

public record KnowledgeBaseResponse(long id, String name, String description, KnowledgeBaseStatus status) { }
