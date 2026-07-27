package com.sagevault.kb.knowledgebase.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sagevault.kb.knowledgebase.domain.CreateKnowledgeBaseRequest;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseEntity;
import com.sagevault.kb.knowledgebase.domain.KnowledgeBaseStatus;
import com.sagevault.kb.knowledgebase.service.impl.KnowledgeBaseServiceImpl;
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
        };
        KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(raceMapper, (operation, id) -> { });

        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest(existing.getName(), "duplicate")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称已存在");
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
