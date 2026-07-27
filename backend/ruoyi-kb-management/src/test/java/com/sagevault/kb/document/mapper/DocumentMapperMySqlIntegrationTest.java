package com.sagevault.kb.document.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.document.domain.DocumentEntity;
import com.sagevault.kb.document.domain.DocumentStatus;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.mapper.KnowledgeBaseMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentMapperMySqlIntegrationTest {
    private static final String JDBC_URL_ENV = "SAGE_VAULT_MYSQL_TEST_URL";
    private static final String JDBC_USER_ENV = "SAGE_VAULT_MYSQL_TEST_USERNAME";
    private static final String JDBC_PASSWORD_ENV = "SAGE_VAULT_MYSQL_TEST_PASSWORD";

    private JdbcTemplate jdbc;
    private DocumentMapper mapper;
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
                        + "AND table_name = 'sv_enterprise_document')", Boolean.class)),
                "requires the production document schema");
        Configuration configuration = new Configuration();
        configuration.addMapper(DocumentMapper.class);
        configuration.addMapper(KnowledgeBaseMapper.class);
        SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(
                new ClassPathResource("mapper/document/DocumentMapper.xml"),
                new ClassPathResource("mapper/knowledgebase/KnowledgeBaseMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory.getObject());
        MapperFactoryBean<DocumentMapper> documentFactory = new MapperFactoryBean<>(DocumentMapper.class);
        documentFactory.setSqlSessionTemplate(sessions);
        documentFactory.afterPropertiesSet();
        mapper = documentFactory.getObject();
        MapperFactoryBean<KnowledgeBaseMapper> knowledgeBaseFactory = new MapperFactoryBean<>(KnowledgeBaseMapper.class);
        knowledgeBaseFactory.setSqlSessionTemplate(sessions);
        knowledgeBaseFactory.afterPropertiesSet();
        knowledgeBaseMapper = knowledgeBaseFactory.getObject();
        prefix = "doc-mapper-" + UUID.randomUUID() + "-";
        knowledgeBaseId = createKnowledgeBase();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && prefix != null) {
            jdbc.update("DELETE FROM sv_enterprise_document WHERE filename LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM sv_knowledge_base WHERE name LIKE ?", prefix + "%");
        }
    }

    @Test
    void xmlMapperMapsGeneratedKeysAndEnforcesKbNameUniqueness() {
        DocumentEntity first = entity("First.txt", DocumentStatus.PROCESSING);
        mapper.insert(first);
        DocumentEntity second = entity("Second.txt", DocumentStatus.AVAILABLE);
        mapper.insert(second);

        assertThat(first.getId()).isPositive();
        assertThat(mapper.findById(second.getId()).getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThatThrownBy(() -> mapper.insert(entity("first.txt", DocumentStatus.PROCESSING)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findsDocumentsByKnowledgeBaseInStableOrder() {
        DocumentEntity first = entity("Alpha.txt", DocumentStatus.PROCESSING);
        mapper.insert(first);
        DocumentEntity second = entity("Beta.txt", DocumentStatus.FAILED);
        mapper.insert(second);

        assertThat(mapper.findByKbId(knowledgeBaseId)).extracting(DocumentEntity::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void updatesStatusAndErrorMessage() {
        DocumentEntity entity = entity("Update.txt", DocumentStatus.PROCESSING);
        mapper.insert(entity);

        mapper.updateStatus(entity.getId(), DocumentStatus.FAILED.name(), "parse failed");

        DocumentEntity updated = mapper.findById(entity.getId());
        assertThat(updated.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(updated.getErrorMessage()).isEqualTo("parse failed");
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

    private DocumentEntity entity(String filename, DocumentStatus status) {
        DocumentEntity entity = new DocumentEntity();
        entity.setKbId(knowledgeBaseId);
        entity.setFilename(prefix + filename);
        entity.setNormalizedName((prefix + filename).toLowerCase(java.util.Locale.ROOT));
        entity.setStatus(status);
        entity.setObjectKey("documents/" + knowledgeBaseId + "/" + UUID.randomUUID() + "/" + filename);
        entity.setSize((long) filename.length());
        entity.setErrorMessage("");
        return entity;
    }
}
