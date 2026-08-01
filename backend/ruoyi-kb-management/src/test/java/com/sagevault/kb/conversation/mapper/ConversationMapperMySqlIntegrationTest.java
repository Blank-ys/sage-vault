package com.sagevault.kb.conversation.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sagevault.kb.conversation.domain.ConversationEntity;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConversationMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private ConversationMapper mapper;
    private String prefix;
    private long knowledgeBaseId;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv(JDBC_URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), "requires an explicitly configured MySQL test database");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv(JDBC_USER_ENV),
                System.getenv(JDBC_PASSWORD_ENV)));
        Assumptions.assumeTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'sv_conversation')", Boolean.class)),
                "requires the production conversation schema");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(ConversationMapper.class);
        org.mybatis.spring.SqlSessionFactoryBean sessionFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        sessionFactory.setDataSource(jdbc.getDataSource());
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource(
                "mapper/conversation/ConversationMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        MapperFactoryBean<ConversationMapper> factory = new MapperFactoryBean<>(ConversationMapper.class);
        factory.setSqlSessionTemplate(sessions);
        factory.afterPropertiesSet();
        mapper = factory.getObject();
        prefix = "conversation-mapper-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sv_knowledge_base (name, normalized_name, description, status) VALUES (?, ?, '', 'AVAILABLE')",
                prefix, prefix.toLowerCase(java.util.Locale.ROOT));
        knowledgeBaseId = jdbc.queryForObject("SELECT id FROM sv_knowledge_base WHERE name = ?", Long.class, prefix);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE r FROM sv_qa_record r JOIN sv_conversation c ON r.conversation_id = c.id "
                    + "JOIN sv_knowledge_base kb ON c.knowledge_base_id = kb.id WHERE kb.name = ?", prefix);
            jdbc.update("DELETE c FROM sv_conversation c JOIN sv_knowledge_base kb ON c.knowledge_base_id = kb.id WHERE kb.name = ?",
                    prefix);
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name = ?", prefix);
        }
    }

    @Test
    void mapsGeneratedKeysFieldsAndMissingRows() {
        ConversationEntity conversation = insert(7L);

        assertThat(conversation.getId()).isPositive();
        assertThat(mapper.findById(conversation.getId()))
                .extracting(ConversationEntity::getUserId, ConversationEntity::getKnowledgeBaseId,
                        ConversationEntity::getTitle)
                .containsExactly(7L, knowledgeBaseId, "");
        assertThat(mapper.findById(conversation.getId()).getCreatedAt()).isNotNull();
        assertThat(mapper.findById(conversation.getId()).getUpdatedAt()).isNotNull();
        assertThat(mapper.findById(Long.MAX_VALUE)).isNull();
    }

    @Test
    void findByUserReturnsOnlyOwnRowsMostRecentFirst() {
        ConversationEntity older = insert(7L);
        ConversationEntity newer = insert(7L);
        ConversationEntity foreign = insert(8L);

        assertThat(mapper.findByUser(7L)).extracting(ConversationEntity::getId)
                .containsExactly(newer.getId(), older.getId())
                .doesNotContain(foreign.getId());
    }

    @Test
    void updateTitleAndDeleteOnlyAffectTheOwner() {
        ConversationEntity conversation = insert(7L);

        assertThat(mapper.updateTitle(conversation.getId(), 8L, "别人的标题")).isZero();
        assertThat(mapper.updateTitle(conversation.getId(), 7L, "我的标题")).isOne();
        assertThat(mapper.findById(conversation.getId()).getTitle()).isEqualTo("我的标题");

        assertThat(mapper.deleteOwned(conversation.getId(), 8L)).isZero();
        assertThat(mapper.deleteOwned(conversation.getId(), 7L)).isOne();
        assertThat(mapper.findById(conversation.getId())).isNull();
    }

    @Test
    void deletingConversationCascadesItsQaRecords() {
        ConversationEntity conversation = insert(7L);
        jdbc.update("INSERT INTO sv_qa_record (conversation_id, user_id, request_id, generation_id, question, answer, status) "
                        + "VALUES (?, 7, ?, ?, '问题正文', '', 'STARTED')",
                conversation.getId(), prefix + "-req", prefix + "-gen");

        mapper.deleteOwned(conversation.getId(), 7L);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_qa_record WHERE conversation_id = ?", Integer.class,
                conversation.getId())).isZero();
    }

    @Test
    void selectForStreamingReturnsOwnedRowAndExcludesOthers() {
        ConversationEntity mine = insert(7L);
        ConversationEntity theirs = insert(8L);

        assertThat(mapper.selectForStreaming(mine.getId(), 7L)).isNotNull();
        assertThat(mapper.selectForStreaming(mine.getId(), 7L).getId()).isEqualTo(mine.getId());
        assertThat(mapper.selectForStreaming(mine.getId(), 8L)).isNull();
        assertThat(mapper.selectForStreaming(theirs.getId(), 7L)).isNull();
    }

    private ConversationEntity insert(long userId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setUserId(userId);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setTitle("");
        mapper.insert(conversation);
        return conversation;
    }
}
