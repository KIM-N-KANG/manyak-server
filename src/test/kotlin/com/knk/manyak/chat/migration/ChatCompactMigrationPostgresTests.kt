package com.knk.manyak.chat.migration

import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/** H2 ddl-auto와 별도로 구버전 데이터·구버전 INSERT·CHECK를 실제 PostgreSQL에서 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatCompactMigrationPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "compact-migration-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private var story = 0L
    private var oldChat = 0L
    @BeforeAll fun migrate() {
        pg.start()
        val datasource = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "compact-migration-test")
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("115").load().migrate()
        jdbc = JdbcTemplate(datasource)
        story = jdbc.queryForObject("INSERT INTO stories(title) VALUES ('요약 마이그레이션') RETURNING id", Long::class.java)!!
        oldChat = jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING id", Long::class.java, story)!!
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate()
    }
    @AfterAll fun stop() { pg.stop() }
    @Test fun `요약 끝 턴 마이그레이션은 기존 행과 컬럼 생략 삽입에 영을 채운다`() {
        assertThat(jdbc.queryForObject("SELECT summary_through_turn FROM story_chats WHERE id = ?", Int::class.java, oldChat)).isZero()
        assertThat(jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING summary_through_turn", Int::class.java, story)).isZero()
        for (value in listOf("NULL", "-1")) assertThatThrownBy {
            jdbc.update("UPDATE story_chats SET summary_through_turn = $value WHERE id = ?", oldChat)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
    @Test fun `롤백 뒤 요약 일괄 비우기는 이전 이미지가 남긴 요약을 모두 지운다`() {
        jdbc.update("INSERT INTO story_chats(story_id,current_turn,summary,summary_through_turn,regenerated_count) VALUES (?,10,'낡은 요약',10,1),(?,20,'낡은 요약',20,3)", story, story)
        jdbc.update("UPDATE story_chats SET summary = NULL, summary_through_turn = 0 WHERE summary_through_turn > 0")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_chats WHERE summary IS NOT NULL OR summary_through_turn <> 0", Long::class.java)).isZero()
    }
    @Test fun `요약 호출 기록 제약은 chat_compact를 허용한다`() {
        assertThat(jdbc.update("INSERT INTO ai_call_logs(request_id,caller_service,feature,status,turn_number) VALUES ('compact-test','manyak-server','chat_compact','STARTED',10)")).isEqualTo(1)
    }
}
