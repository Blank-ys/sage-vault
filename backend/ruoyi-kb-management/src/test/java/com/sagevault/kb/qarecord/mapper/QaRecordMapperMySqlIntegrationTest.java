package com.sagevault.kb.qarecord.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QaRecordMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private QaRecordMapper mapper;
    private String prefix;
    private long conversationId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(JDBC_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), "requires an explicitly configured MySQL test database");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv(JDBC_USER_ENV),
                System.getenv(JDBC_PASSWORD_ENV)));
        Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sv_qa_record')", Boolean.class)),
                "requires the production QA record schema");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(QaRecordMapper.class);
        org.mybatis.spring.SqlSessionFactoryBean sessionFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        sessionFactory.setDataSource(jdbc.getDataSource());
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/qarecord/QaRecordMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        MapperFactoryBean<QaRecordMapper> factory = new MapperFactoryBean<>(QaRecordMapper.class);
        factory.setSqlSessionTemplate(sessions);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
        prefix = "qa-mapper-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sv_knowledge_base (name, normalized_name, description, status) VALUES (?, ?, '', 'AVAILABLE')",
                prefix, prefix.toLowerCase(java.util.Locale.ROOT));
        long knowledgeBaseId = jdbc.queryForObject("SELECT id FROM sv_knowledge_base WHERE name = ?", Long.class, prefix);
        jdbc.update("INSERT INTO sv_conversation (user_id, knowledge_base_id) VALUES (1, ?)", knowledgeBaseId);
        conversationId = jdbc.queryForObject("SELECT id FROM sv_conversation WHERE knowledge_base_id = ?", Long.class,
                knowledgeBaseId);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE FROM sv_qa_record WHERE request_id LIKE ?", prefix + "%");
            jdbc.update("DELETE c FROM sv_conversation c JOIN sv_knowledge_base kb ON c.knowledge_base_id = kb.id WHERE kb.name = ?", prefix);
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name = ?", prefix);
        }
    }

    @Test
    void mapsGeneratedKeysEnumNamesAndConditionalTerminalUpdates() {
        QaRecordEntity record = record("one");
        mapper.insert(record);

        assertThat(record.getId()).isPositive();
        assertThat(mapper.findByGenerationId(record.getGenerationId()).getStatus()).isEqualTo(QaRecordStatus.STARTED);
        assertThat(mapper.updateTerminalState(record.getGenerationId(), QaRecordStatus.REFUSED, "refused")).isEqualTo(1);
        assertThat(mapper.updateTerminalState(record.getGenerationId(), QaRecordStatus.UNFINISHED, "")).isZero();
        assertThat(mapper.findByGenerationId(record.getGenerationId()).getStatus()).isEqualTo(QaRecordStatus.REFUSED);
    }

    @Test
    void rejectsUnknownPersistedStatusValues() {
        QaRecordEntity record = record("unknown");
        mapper.insert(record);
        jdbc.update("UPDATE sv_qa_record SET status = 'UNKNOWN' WHERE id = ?", record.getId());

        assertThatThrownBy(() -> mapper.findByGenerationId(record.getGenerationId())).isNotNull();
    }

    @Test
    void listsCountsAndDeletesRecordsOfOneConversation() {
        QaRecordEntity first = record("first");
        QaRecordEntity second = record("second");
        mapper.insert(first);
        mapper.insert(second);

        assertThat(mapper.countByConversation(conversationId)).isEqualTo(2);
        assertThat(mapper.findByConversation(conversationId))
                .extracting(QaRecordEntity::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(mapper.findByConversation(conversationId).get(0).getCreatedAt()).isNotNull();

        assertThat(mapper.deleteByConversation(conversationId)).isEqualTo(2);
        assertThat(mapper.countByConversation(conversationId)).isZero();
    }

    private QaRecordEntity record(String suffix) {
        QaRecordEntity record = new QaRecordEntity();
        record.setConversationId(conversationId);
        record.setUserId(1L);
        record.setRequestId(prefix + "-request-" + suffix);
        record.setGenerationId(prefix + "-generation-" + suffix);
        record.setQuestion("question");
        record.setAnswer("");
        record.setStatus(QaRecordStatus.STARTED);
        return record;
    }
}
