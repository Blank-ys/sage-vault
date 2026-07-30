package com.sagevault.kb.document.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.document.domain.IndexingTaskEntity;
import com.sagevault.kb.document.domain.IndexingTaskStatus;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IndexingTaskMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private DocumentMapper documentMapper;
    private IndexingTaskMapper indexingTaskMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private String prefix;
    private long knowledgeBaseId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(JDBC_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), "requires an explicitly configured MySQL test database");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv(JDBC_USER_ENV), System.getenv(JDBC_PASSWORD_ENV));
        jdbc = new JdbcTemplate(dataSource);
        Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sv_document_indexing_task')", Boolean.class)),
                "requires the production indexing task schema");
        Configuration configuration = new Configuration();
        configuration.addMapper(DocumentMapper.class);
        configuration.addMapper(IndexingTaskMapper.class);
        configuration.addMapper(KnowledgeBaseMapper.class);
        SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(
                new ClassPathResource("mapper/document/DocumentMapper.xml"),
                new ClassPathResource("mapper/document/IndexingTaskMapper.xml"),
                new ClassPathResource("mapper/knowledgebase/KnowledgeBaseMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        MapperFactoryBean<DocumentMapper> documentFactory = new MapperFactoryBean<>(DocumentMapper.class);
        documentFactory.setSqlSessionTemplate(sessions);
        documentFactory.afterPropertiesSet();
        documentMapper = documentFactory.getObject();
        MapperFactoryBean<IndexingTaskMapper> indexingTaskFactory = new MapperFactoryBean<>(
                IndexingTaskMapper.class);
        indexingTaskFactory.setSqlSessionTemplate(sessions);
        indexingTaskFactory.afterPropertiesSet();
        indexingTaskMapper = indexingTaskFactory.getObject();
        MapperFactoryBean<KnowledgeBaseMapper> knowledgeBaseFactory = new MapperFactoryBean<>(KnowledgeBaseMapper.class);
        knowledgeBaseFactory.setSqlSessionTemplate(sessions);
        knowledgeBaseFactory.afterPropertiesSet();
        knowledgeBaseMapper = knowledgeBaseFactory.getObject();
        prefix = "idx-task-" + UUID.randomUUID() + "-";
        knowledgeBaseId = createKnowledgeBase();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE FROM sv_document_indexing_task WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM sv_enterprise_document WHERE filename LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name LIKE ?", prefix + "%");
        }
    }

    @Test
    void insertsAndFindsTaskByTaskId() {
        DocumentEntity document = createDocument("Task.txt");
        String taskId = prefix + "task-1";
        IndexingTaskEntity task = taskEntity(document.getId(), taskId, IndexingTaskStatus.PROCESSING);

        indexingTaskMapper.insert(task);
        IndexingTaskEntity found = indexingTaskMapper.findByTaskId(taskId);

        assertThat(found).isNotNull();
        assertThat(found.getDocumentId()).isEqualTo(document.getId());
        assertThat(found.getAttempt()).isEqualTo(1);
        assertThat(found.getStatus()).isEqualTo(IndexingTaskStatus.PROCESSING);
    }

    @Test
    void updateTerminalStateOnlyWhenAttemptIsHigher() {
        DocumentEntity document = createDocument("Attempt.txt");
        String taskId = prefix + "task-idem";
        IndexingTaskEntity task = taskEntity(document.getId(), taskId, IndexingTaskStatus.PROCESSING);
        indexingTaskMapper.insert(task);

        int first = indexingTaskMapper.updateTerminalState(taskId, 1, IndexingTaskStatus.COMPLETED.name(), "",
                LocalDateTime.now());
        int second = indexingTaskMapper.updateTerminalState(taskId, 1, IndexingTaskStatus.COMPLETED.name(), "",
                LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(indexingTaskMapper.findByTaskId(taskId).getStatus()).isEqualTo(IndexingTaskStatus.COMPLETED);
    }

    @Test
    void updateTerminalStateAdvancesAttempt() {
        DocumentEntity document = createDocument("Advance.txt");
        String taskId = prefix + "task-adv";
        IndexingTaskEntity task = taskEntity(document.getId(), taskId, IndexingTaskStatus.PROCESSING);
        indexingTaskMapper.insert(task);

        int first = indexingTaskMapper.updateTerminalState(taskId, 1, IndexingTaskStatus.FAILED.name(), "error",
                LocalDateTime.now());
        int second = indexingTaskMapper.updateTerminalState(taskId, 2, IndexingTaskStatus.COMPLETED.name(), "",
                LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        IndexingTaskEntity updated = indexingTaskMapper.findByTaskId(taskId);
        assertThat(updated.getAttempt()).isEqualTo(2);
        assertThat(updated.getStatus()).isEqualTo(IndexingTaskStatus.COMPLETED);
    }

    @Test
    void findLatestByDocumentIdReturnsHighestAttempt() {
        DocumentEntity document = createDocument("Latest.txt");
        String firstTaskId = prefix + "task-latest-1";
        String secondTaskId = prefix + "task-latest-2";
        IndexingTaskEntity firstTask = taskEntity(document.getId(), firstTaskId, IndexingTaskStatus.PROCESSING);
        indexingTaskMapper.insert(firstTask);
        IndexingTaskEntity secondTask = taskEntity(document.getId(), secondTaskId, IndexingTaskStatus.PROCESSING);
        secondTask.setAttempt(2);
        indexingTaskMapper.insert(secondTask);

        IndexingTaskEntity latest = indexingTaskMapper.findLatestByDocumentId(document.getId());

        assertThat(latest).isNotNull();
        assertThat(latest.getTaskId()).isEqualTo(secondTaskId);
        assertThat(latest.getAttempt()).isEqualTo(2);
    }

    private long createKnowledgeBase() {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setName(prefix + "KB");
        knowledgeBase.setNormalizedName((prefix + "KB").toLowerCase(java.util.Locale.ROOT));
        knowledgeBase.setDescription("test");
        knowledgeBase.setStatus(KnowledgeBaseStatus.AVAILABLE);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase.getId();
    }

    private DocumentEntity createDocument(String filename) {
        DocumentEntity entity = new DocumentEntity();
        entity.setKbId(knowledgeBaseId);
        entity.setFilename(prefix + filename);
        entity.setNormalizedName((prefix + filename).toLowerCase(java.util.Locale.ROOT));
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setObjectKey("documents/" + knowledgeBaseId + "/" + UUID.randomUUID() + "/" + filename);
        entity.setSize((long) filename.length());
        entity.setErrorMessage("");
        documentMapper.insert(entity);
        return entity;
    }

    private static IndexingTaskEntity taskEntity(long documentId, String taskId, IndexingTaskStatus status) {
        IndexingTaskEntity task = new IndexingTaskEntity();
        task.setDocumentId(documentId);
        task.setTaskId(taskId);
        task.setAttempt(1);
        task.setStatus(status);
        task.setErrorMessage("");
        return task;
    }
}
