package com.sagevault.kb.knowledgebase.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.service.impl.KnowledgeBaseServiceImpl;
import com.sagevault.kb.knowledgebase.service.port.KnowledgeBaseContentCleaner;
import com.sagevault.kb.platform.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class KnowledgeBaseMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private KnowledgeBaseMapper mapper;
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(JDBC_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), "requires an explicitly configured MySQL test database");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                System.getenv(JDBC_USER_ENV), System.getenv(JDBC_PASSWORD_ENV));
        jdbc = new JdbcTemplate(dataSource);
        Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sv_knowledge_base')", Boolean.class)),
                "requires the production knowledge-base schema");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(KnowledgeBaseMapper.class);
        org.mybatis.spring.SqlSessionFactoryBean sessionFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/knowledgebase/KnowledgeBaseMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        MapperFactoryBean<KnowledgeBaseMapper> factory = new MapperFactoryBean<>(KnowledgeBaseMapper.class);
        factory.setSqlSessionTemplate(sessions);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
        prefix = "mapper-" + UUID.randomUUID() + "-";
        jdbc.update("DELETE FROM sv_knowledge_base WHERE name LIKE ?", prefix + "%");
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name LIKE ?", prefix + "%");
        }
    }

    @Test
    void xmlMapperMapsGeneratedKeysEnumsUniquenessTimestampsAndStableOrdering() throws Exception {
        KnowledgeBaseEntity first = entity("First", KnowledgeBaseStatus.AVAILABLE);
        mapper.insert(first);
        KnowledgeBaseEntity second = entity("Second", KnowledgeBaseStatus.DELETING);
        mapper.insert(second);

        assertThat(first.getId()).isPositive();
        assertThat(mapper.findById(second.getId()).getStatus()).isEqualTo(KnowledgeBaseStatus.DELETING);
        assertThatThrownBy(() -> mapper.insert(entity("second", KnowledgeBaseStatus.AVAILABLE))).isNotNull();

        jdbc.update("UPDATE sv_knowledge_base SET updated_at = '2000-01-01 00:00:00' WHERE id IN (?, ?)",
                first.getId(), second.getId());
        assertThat(mapper.findAll()).extracting(KnowledgeBaseEntity::getId)
                .containsSubsequence(second.getId(), first.getId());
        first.setDescription("updated-" + UUID.randomUUID());
        mapper.update(first);
        assertThat(jdbc.queryForObject("SELECT updated_at > '2000-01-01 00:00:00' FROM sv_knowledge_base WHERE id = ?",
                Boolean.class, first.getId())).isTrue();
        assertThat(mapper.findAll()).extracting(KnowledgeBaseEntity::getId)
                .containsSubsequence(first.getId(), second.getId());
        assertThat(mapper.findByStatus(KnowledgeBaseStatus.AVAILABLE.name()))
                .extracting(KnowledgeBaseEntity::getId).contains(first.getId());
    }

    @Test
    void serviceMapsDatabaseUniqueKeyRaceToKnowledgeBaseConflict() {
        KnowledgeBaseEntity existing = entity("Duplicate", KnowledgeBaseStatus.AVAILABLE);
        mapper.insert(existing);
        KnowledgeBaseMapper raceMapper = new KnowledgeBaseMapper() {
            @Override public int insert(KnowledgeBaseEntity entity) { return mapper.insert(entity); }
            @Override public int update(KnowledgeBaseEntity entity) { return mapper.update(entity); }
            @Override public KnowledgeBaseEntity findById(long id) { return mapper.findById(id); }
            @Override public KnowledgeBaseEntity findByNormalizedName(String normalizedName) { return null; }
            @Override public java.util.List<KnowledgeBaseEntity> findAll() { return mapper.findAll(); }
            @Override public java.util.List<KnowledgeBaseEntity> findByStatus(String status) { return mapper.findByStatus(status); }
            @Override public java.util.List<KnowledgeBaseEntity> findByIds(java.util.Collection<Long> ids) { return mapper.findByIds(ids); }
            @Override public int updateStatusIfCurrentStatus(long id, String newStatus, String errorMessage, String currentStatus) {
                return mapper.updateStatusIfCurrentStatus(id, newStatus, errorMessage, currentStatus);
            }
            @Override public int startCleanupIfCurrentStatus(long id, String currentStatus) {
                return mapper.startCleanupIfCurrentStatus(id, currentStatus);
            }
            @Override public int incrementCleanupAttempt(long id) { return mapper.incrementCleanupAttempt(id); }
            @Override public int deleteByIdIfDeleting(long id) { return mapper.deleteByIdIfDeleting(id); }
        };
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(raceMapper,
                mock(com.sagevault.kb.knowledgebase.service.port.ManagementAudit.class),
                new KnowledgeBaseContentCleaner() {
                    @Override public CleanupProgress cleanupContent(long knowledgeBaseId) {
                        return CleanupProgress.inProgress(0);
                    }
                    @Override public int retryFailedContent(long knowledgeBaseId) { return 0; }
                });

        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest(existing.getName(), "duplicate")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称已存在");
    }

    /** 级联删除依赖的 CAS 状态流转、清理计数与条件删除必须在真实 MySQL 上成立。 */
    @Test
    void cascadeDeleteSqlEnforcesStatusGuardsAgainstRealDatabase() {
        KnowledgeBaseEntity knowledgeBase = entity("Cascade", KnowledgeBaseStatus.AVAILABLE);
        mapper.insert(knowledgeBase);
        long id = knowledgeBase.getId();

        // 仅当状态匹配时才流转：过期的期望状态不得改写记录
        assertThat(mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETING.name(), "",
                KnowledgeBaseStatus.DELETE_FAILED.name())).isZero();
        assertThat(mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETING.name(), "",
                KnowledgeBaseStatus.AVAILABLE.name())).isEqualTo(1);
        assertThat(mapper.findById(id).getStatus()).isEqualTo(KnowledgeBaseStatus.DELETING);

        // 清理计数随每轮推进递增，供残留检测使用
        assertThat(mapper.incrementCleanupAttempt(id)).isEqualTo(1);
        assertThat(mapper.findById(id).getCleanupAttempt()).isEqualTo(1);

        // 失败原因随状态一起落库，供知识管理员诊断残留
        mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETE_FAILED.name(), "Milvus 删除失败",
                KnowledgeBaseStatus.DELETING.name());
        assertThat(mapper.findById(id).getErrorMessage()).isEqualTo("Milvus 删除失败");

        // 非 DELETING 的知识库不得被后台清理删除，避免误删仍在使用的知识库
        assertThat(mapper.deleteByIdIfDeleting(id)).isZero();
        assertThat(mapper.findById(id)).isNotNull();

        // 重新进入 DELETING 后才允许移除活动记录
        mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETING.name(), "",
                KnowledgeBaseStatus.DELETE_FAILED.name());
        assertThat(mapper.deleteByIdIfDeleting(id)).isEqualTo(1);
        assertThat(mapper.findById(id)).isNull();
        assertThat(mapper.findByIds(java.util.List.of(id))).isEmpty();
    }

    /**
     * 重试删除必须在同一条语句里归零 cleanup_attempt。
     *
     * <p>上一轮失败时清理预算可能已被 FAILSAFE 耗尽，若重试沿用旧计数，
     * 后台第一轮就会再次越过阈值并立刻判失败，重试将永远无法成功。
     */
    @Test
    void retryStartResetsCleanupAttemptAtomicallyAgainstRealDatabase() {
        KnowledgeBaseEntity knowledgeBase = entity("RetryBudget", KnowledgeBaseStatus.AVAILABLE);
        mapper.insert(knowledgeBase);
        long id = knowledgeBase.getId();
        assertThat(id).isPositive();
        assertThat(mapper.findById(id)).as("inserted knowledge base must be retrievable").isNotNull();

        assertThat(mapper.startCleanupIfCurrentStatus(id, KnowledgeBaseStatus.AVAILABLE.name())).isEqualTo(1);
        for (int i = 0; i < 20; i++) {
            mapper.incrementCleanupAttempt(id);
        }
        mapper.updateStatusIfCurrentStatus(id, KnowledgeBaseStatus.DELETE_FAILED.name(), "[向量清理] 失败",
                KnowledgeBaseStatus.DELETING.name());
        assertThat(mapper.findById(id)).as("knowledge base must survive failure transition").isNotNull();
        assertThat(mapper.findById(id).getCleanupAttempt()).isEqualTo(20);

        // 状态不匹配时不得启动清理，避免把可用知识库拖进删除流程
        assertThat(mapper.startCleanupIfCurrentStatus(id, KnowledgeBaseStatus.AVAILABLE.name())).isZero();

        assertThat(mapper.startCleanupIfCurrentStatus(id, KnowledgeBaseStatus.DELETE_FAILED.name())).isEqualTo(1);
        KnowledgeBaseEntity retried = mapper.findById(id);
        assertThat(retried.getStatus()).isEqualTo(KnowledgeBaseStatus.DELETING);
        assertThat(retried.getCleanupAttempt()).isZero();
        assertThat(retried.getErrorMessage()).isEmpty();

        mapper.deleteByIdIfDeleting(id);
    }

    /**
     * 历史会话、问答与反馈必须能在知识库活动记录被删除后继续存在，
     * 否则"历史可读"与"删除知识库不删除历史反馈"都无法兑现。
     */
    @Test
    void conversationHistoryAndFeedbackSurviveKnowledgeBaseDeletion() {
        KnowledgeBaseEntity knowledgeBase = entity("HistoryOwner", KnowledgeBaseStatus.DELETING);
        mapper.insert(knowledgeBase);
        long id = knowledgeBase.getId();
        long userId = 999_000_001L;
        String marker = prefix + "history";
        jdbc.update("INSERT INTO sv_conversation (user_id, knowledge_base_id, title) VALUES (?, ?, ?)",
                userId, id, marker);
        Long conversationId = jdbc.queryForObject(
                "SELECT id FROM sv_conversation WHERE user_id = ? AND knowledge_base_id = ?", Long.class, userId, id);
        jdbc.update("INSERT INTO sv_qa_record (conversation_id, user_id, request_id, generation_id, "
                        + "question, answer, status) VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED')",
                conversationId, userId, marker + "-req", marker + "-gen", "报销要多久？", "10 个工作日内。");
        Long qaId = jdbc.queryForObject("SELECT id FROM sv_qa_record WHERE request_id = ?", Long.class,
                marker + "-req");
        jdbc.update("INSERT INTO sv_qa_feedback (qa_id, user_id, category, comment) VALUES (?, ?, ?, ?)",
                qaId, userId, "INCOMPLETE_ANSWER", "希望补充跨境差旅");
        try {
            assertThat(mapper.deleteByIdIfDeleting(id)).isEqualTo(1);

            // 知识库消失后会话仍在，且其 knowledge_base_id 解析不到任何活动记录
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sv_conversation WHERE user_id = ? AND knowledge_base_id = ?",
                    Integer.class, userId, id)).isEqualTo(1);
            assertThat(mapper.findByIds(java.util.List.of(id))).isEmpty();

            // 问答与反馈都不随知识库删除而消失
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_qa_record WHERE id = ?", Integer.class, qaId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_qa_feedback WHERE qa_id = ?", Integer.class, qaId))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM sv_qa_feedback WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sv_qa_record WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sv_conversation WHERE user_id = ?", userId);
        }
    }

    private KnowledgeBaseEntity entity(String name, KnowledgeBaseStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setName(prefix + name);
        entity.setNormalizedName((prefix + name).toLowerCase(java.util.Locale.ROOT));
        entity.setDescription("description");
        entity.setStatus(status);
        return entity;
    }
}
