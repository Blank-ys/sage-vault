package com.sagevault.kb.feedback.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.feedback.domain.AdminFeedbackQuery;
import com.sagevault.kb.feedback.domain.FeedbackCategory;
import com.sagevault.kb.feedback.domain.FeedbackEntity;
import com.sagevault.kb.feedback.domain.FeedbackStatus;
import com.sagevault.kb.qarecord.domain.QaRecordEntity;
import com.sagevault.kb.qarecord.domain.QaRecordStatus;
import com.sagevault.kb.qarecord.mapper.QaRecordMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FeedbackMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private FeedbackMapper mapper;
    private QaRecordMapper qaRecords;
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
                        + "AND table_name = 'sv_qa_feedback')", Boolean.class)),
                "requires the production feedback schema");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(FeedbackMapper.class);
        configuration.addMapper(QaRecordMapper.class);
        org.mybatis.spring.SqlSessionFactoryBean sessionFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        sessionFactory.setDataSource(jdbc.getDataSource());
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(
                new org.springframework.core.io.ClassPathResource("mapper/feedback/FeedbackMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/qarecord/QaRecordMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        mapper = mapperOf(FeedbackMapper.class, sessions);
        qaRecords = mapperOf(QaRecordMapper.class, sessions);
        prefix = "feedback-mapper-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sv_knowledge_base (name, normalized_name, description, status) "
                + "VALUES (?, ?, '', 'AVAILABLE')", prefix, prefix.toLowerCase(java.util.Locale.ROOT));
        long knowledgeBaseId = jdbc.queryForObject("SELECT id FROM sv_knowledge_base WHERE name = ?", Long.class,
                prefix);
        jdbc.update("INSERT INTO sv_conversation (user_id, knowledge_base_id) VALUES (1, ?)", knowledgeBaseId);
        conversationId = jdbc.queryForObject("SELECT id FROM sv_conversation WHERE knowledge_base_id = ?", Long.class,
                knowledgeBaseId);
    }

    private static <T> T mapperOf(Class<T> type, SqlSessionTemplate sessions) throws Exception {
        MapperFactoryBean<T> factory = new MapperFactoryBean<>(type);
        factory.setSqlSessionTemplate(sessions);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE FROM sv_qa_record WHERE request_id LIKE ?", prefix + "%");
            jdbc.update("DELETE c FROM sv_conversation c JOIN sv_knowledge_base kb ON c.knowledge_base_id = kb.id "
                    + "WHERE kb.name = ?", prefix);
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name = ?", prefix);
        }
    }

    @Test
    void storesCategoryEnumAndDefaultsToPendingForTheAdmin() {
        long qaId = insertRecord("one");

        mapper.insert(feedback(qaId, FeedbackCategory.WRONG_ANSWER, "答案与文档不一致"));

        FeedbackEntity stored = mapper.findByQaId(qaId);
        assertThat(stored.getId()).isPositive();
        assertThat(stored.getCategory()).isEqualTo(FeedbackCategory.WRONG_ANSWER);
        assertThat(stored.getComment()).isEqualTo("答案与文档不一致");
        assertThat(stored.getStatus()).isEqualTo(FeedbackStatus.PENDING);
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsASecondFeedbackForTheSameQaRecord() {
        long qaId = insertRecord("dup");
        mapper.insert(feedback(qaId, FeedbackCategory.OTHER, "第一次"));

        assertThatThrownBy(() -> mapper.insert(feedback(qaId, FeedbackCategory.OTHER, "第二次")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void deletesFeedbackContentWhenTheUserDeletesTheConversation() {
        long qaId = insertRecord("cascade");
        mapper.insert(feedback(qaId, FeedbackCategory.INCOMPLETE_ANSWER, "只答了一半"));

        qaRecords.deleteByConversation(conversationId);

        // 用户删除会话后反馈正文必须一并消失，不能在管理端留下残留内容
        assertThat(mapper.findByQaId(qaId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_qa_feedback WHERE qa_id = ?", Integer.class, qaId))
                .isZero();
    }

    @Test
    void looksUpSubmittedFeedbackForAWholeHistoryPage() {
        long withFeedback = insertRecord("with");
        long withoutFeedback = insertRecord("without");
        mapper.insert(feedback(withFeedback, FeedbackCategory.NO_ANSWER_FOUND, ""));

        List<FeedbackEntity> found = mapper.findByQaIds(List.of(withFeedback, withoutFeedback));

        assertThat(found).extracting(FeedbackEntity::getQaId).containsExactly(withFeedback);
    }

    @Test
    void joinsTheQuestionAndAnswerOnlyForRecordsThatHaveFeedback() {
        long withFeedback = insertRecord("detail-with");
        mapper.insert(feedback(withFeedback, FeedbackCategory.WRONG_ANSWER, "答案不对"));
        long feedbackId = mapper.findByQaId(withFeedback).getId();

        var detail = mapper.findDetailForAdmin(feedbackId);

        assertThat(detail.getQaId()).isEqualTo(withFeedback);
        assertThat(detail.getQuestion()).isEqualTo("question");
        assertThat(detail.getAnswer()).isEqualTo("answer");
        assertThat(detail.getRequestId()).isEqualTo(prefix + "-request-detail-with");
        assertThat(detail.getAnswerStatus()).isEqualTo(QaRecordStatus.COMPLETED);
    }

    @Test
    void returnsNothingForAFeedbackIdThatDoesNotExist() {
        // 详情查询从反馈表出发，未提交反馈的问答没有对应反馈 ID，正文无从取出
        assertThat(mapper.findDetailForAdmin(-1L)).isNull();
    }

    @Test
    void filtersTheAdminQueueByStatusAndPagesIt() {
        long pending = insertRecord("queue-pending");
        long resolved = insertRecord("queue-resolved");
        mapper.insert(feedback(pending, FeedbackCategory.OTHER, "待处理"));
        mapper.insert(feedback(resolved, FeedbackCategory.OTHER, "已处理"));
        long resolvedId = mapper.findByQaId(resolved).getId();
        mapper.updateStatus(resolvedId, FeedbackStatus.RESOLVED, "已核实并修正文档");

        long resolvedTotal = mapper.countForAdmin(AdminFeedbackQuery.of(FeedbackStatus.RESOLVED, 1, 10));
        List<FeedbackEntity> resolvedPage =
                mapper.findForAdmin(AdminFeedbackQuery.of(FeedbackStatus.RESOLVED, 1, 10));

        assertThat(resolvedTotal).isPositive();
        assertThat(resolvedPage).extracting(FeedbackEntity::getId).contains(resolvedId);
        assertThat(resolvedPage).extracting(FeedbackEntity::getStatus)
                .containsOnly(FeedbackStatus.RESOLVED);
    }

    @Test
    void limitsTheAdminQueuePageToTheRequestedSize() {
        mapper.insert(feedback(insertRecord("page-one"), FeedbackCategory.OTHER, "1"));
        mapper.insert(feedback(insertRecord("page-two"), FeedbackCategory.OTHER, "2"));

        assertThat(mapper.findForAdmin(AdminFeedbackQuery.of(null, 1, 1))).hasSize(1);
    }

    @Test
    void storesTheAdminNoteWhenResolving() {
        long qaId = insertRecord("note");
        mapper.insert(feedback(qaId, FeedbackCategory.OTHER, "说明"));
        long feedbackId = mapper.findByQaId(qaId).getId();

        mapper.updateStatus(feedbackId, FeedbackStatus.RESOLVED, "已联系文档负责人");

        var detail = mapper.findDetailForAdmin(feedbackId);
        assertThat(detail.getStatus()).isEqualTo(FeedbackStatus.RESOLVED);
        assertThat(detail.getAdminNote()).isEqualTo("已联系文档负责人");
    }

    @Test
    void reportsNoUpdateWhenTheFeedbackIsMissing() {
        assertThat(mapper.updateStatus(-1L, FeedbackStatus.RESOLVED, "")).isZero();
    }

    private long insertRecord(String suffix) {
        QaRecordEntity record = new QaRecordEntity();
        record.setConversationId(conversationId);
        record.setUserId(1L);
        record.setRequestId(prefix + "-request-" + suffix);
        record.setGenerationId(prefix + "-generation-" + suffix);
        record.setQuestion("question");
        record.setAnswer("answer");
        record.setStatus(QaRecordStatus.STARTED);
        qaRecords.insert(record);
        qaRecords.updateTerminalState(record.getGenerationId(), QaRecordStatus.COMPLETED, "answer");
        return record.getId();
    }

    private static FeedbackEntity feedback(long qaId, FeedbackCategory category, String comment) {
        FeedbackEntity entity = new FeedbackEntity();
        entity.setQaId(qaId);
        entity.setUserId(1L);
        entity.setCategory(category);
        entity.setComment(comment);
        entity.setStatus(FeedbackStatus.PENDING);
        entity.setAdminNote("");
        return entity;
    }
}
