package com.knk.manyak.chat.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatInputEditMigrationPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "input-edit-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private var chat = 0L
    @BeforeAll fun migrate() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "input-edit-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("122").load().migrate()
        jdbc = JdbcTemplate(ds)
        val story = jdbc.queryForObject("INSERT INTO stories(title) VALUES ('입력 수정 검증') RETURNING id", Long::class.java)!!
        chat = jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING id", Long::class.java, story)!!
        jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'ASSISTANT','기존 응답',1)", chat)
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
    }
    @AfterAll fun stop() { pg.stop() }
    @Test fun `기존 메시지는 보이고 숨김 뒤 같은 순서 삽입만 허용한다`() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_messages WHERE chat_id=? AND hidden_at IS NULL", Int::class.java, chat)).isEqualTo(1)
        assertThatThrownBy { jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'ASSISTANT','중복',1)", chat) }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("UPDATE story_messages SET hidden_at=now() WHERE chat_id=?", chat)
        jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'ASSISTANT','새 응답',1)", chat)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_messages WHERE chat_id=?", Int::class.java, chat)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='uq_story_messages_order_visible'", String::class.java)).contains("WHERE (hidden_at IS NULL)")
    }
    @Test fun `숨긴 턴 수는 기존 행과 구버전 삽입에 영을 채우고 음수를 거절한다`() {
        assertThat(jdbc.queryForObject("SELECT hidden_turn_count FROM story_chats WHERE id=?", Int::class.java, chat)).isZero()
        val story = jdbc.queryForObject("SELECT story_id FROM story_chats WHERE id=?", Long::class.java, chat)!!
        assertThat(jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING hidden_turn_count", Int::class.java, story)).isZero()
        assertThatThrownBy { jdbc.update("UPDATE story_chats SET hidden_turn_count=-1 WHERE id=?", chat) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
