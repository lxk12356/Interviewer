package com.aims.infra.persistence.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.aims.infra.persistence.mapper.InterviewRoundMapper;
import com.aims.infra.persistence.mapper.InterviewSessionMapper;
import com.aims.infra.persistence.service.InterviewRoundService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 可选真实 PostgreSQL 验证，只允许使用专用、可清空的 *_recovery_test 数据库。 */
@EnabledIfEnvironmentVariable(named = "AIMS_RECOVERY_TEST_JDBC_URL", matches = ".+_recovery_test")
class InterviewRoundDatabaseTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static InterviewRoundService service;
    static Long legacySession;
    static Long legacyRound;

    @BeforeAll
    static void setup() {
        var dataSource = dataSource();
        var flyway =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .cleanDisabled(false)
                        .load();
        flyway.clean();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("2.1.7")
                .load()
                .migrate();
        legacySession =
                jdbc.queryForObject(
                        "INSERT INTO interview_session(status) VALUES ('PAUSED') RETURNING id",
                        Long.class);
        jdbc.update(
                "INSERT INTO interview_round(session_id,seq,question,answer) VALUES"
                        + " (?,1,'主问题','已答')",
                legacySession);
        jdbc.update(
                "INSERT INTO interview_round(session_id,parent_seq,follow_up_index,question) VALUES"
                        + " (?,1,1,'旧追问')",
                legacySession);
        legacyRound =
                jdbc.queryForObject(
                        "INSERT INTO interview_round(session_id,seq,question,answer) VALUES"
                                + " (?,2,'新主问题','已答') RETURNING id",
                        Long.class,
                        legacySession);
        flyway.migrate();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(InterviewRoundService.class);
    }

    static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                System.getenv("AIMS_RECOVERY_TEST_JDBC_URL"),
                System.getenv("AIMS_RECOVERY_TEST_DB_USER"),
                System.getenv("AIMS_RECOVERY_TEST_DB_PASSWORD"));
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @Test
    void migrationBackfillsLatestBusinessRoundWithoutRevivingSkippedFollowUp() {
        assertEquals(
                legacyRound,
                jdbc.queryForObject(
                        "SELECT current_round_id FROM interview_session WHERE id=?",
                        Long.class,
                        legacySession));
        assertEquals("已答", service.getCurrentRound(legacySession).getAnswer());
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM interview_round WHERE session_id=?",
                        Integer.class,
                        legacySession));
    }

    @Test
    void realMapperCreatesMainAndFollowUpAndKeepsSameIdWhenAnswered() {
        Long session =
                jdbc.queryForObject(
                        "INSERT INTO interview_session(status) VALUES ('IN_PROGRESS') RETURNING id",
                        Long.class);
        var main = service.createRound(session, 1, "主问题");
        assertEquals(main.getId(), service.getCurrentRound(session).getId());
        service.updateAnswer(main.getId(), "主问题答案");
        var follow = service.createRound(session, null, "追问", "DEEPEN", 1, 2);
        assertEquals(follow.getId(), service.getCurrentRound(session).getId());
        service.updateAnswer(follow.getId(), "追问答案");
        var current = service.getCurrentRound(session);
        assertNull(current.getSeq());
        assertEquals(1, current.getParentSeq());
        assertEquals(2, current.getFollowUpIndex());
        assertEquals("追问答案", current.getAnswer());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT count(*) FROM interview_round WHERE session_id=?",
                        Integer.class,
                        session));
        assertThrows(RuntimeException.class, () -> service.setCurrentRound(session, legacyRound));
        assertEquals(follow.getId(), service.getCurrentRound(session).getId());
    }

    @Test
    void roundInsertRollsBackIfBookmarkUpdateFails() {
        Long session =
                jdbc.queryForObject(
                        "INSERT INTO interview_session(status) VALUES ('IN_PROGRESS') RETURNING id",
                        Long.class);
        jdbc.execute(
                "CREATE FUNCTION reject_test_bookmark() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'test bookmark failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_test_bookmark BEFORE UPDATE OF current_round_id ON"
                        + " interview_session FOR EACH ROW WHEN (OLD.id = "
                        + session
                        + ") EXECUTE FUNCTION reject_test_bookmark()");
        try {
            assertThrows(RuntimeException.class, () -> service.createRound(session, 1, "应回滚"));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM interview_round WHERE session_id=?",
                            Integer.class,
                            session));
            assertNull(
                    jdbc.queryForObject(
                            "SELECT current_round_id FROM interview_session WHERE id=?",
                            Long.class,
                            session));
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_bookmark ON interview_session");
            jdbc.execute("DROP FUNCTION reject_test_bookmark()");
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return InterviewRoundDatabaseTest.dataSource();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            factory.setDataSource(ds);
            return factory.getObject();
        }

        @Bean
        MapperFactoryBean<InterviewRoundMapper> roundMapper(SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(InterviewRoundMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }

        @Bean
        MapperFactoryBean<InterviewSessionMapper> sessionMapper(SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(InterviewSessionMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }

        @Bean
        InterviewRoundService roundService(
                InterviewRoundMapper rounds, InterviewSessionMapper sessions) {
            return new InterviewRoundServiceImpl(rounds, sessions);
        }
    }
}
