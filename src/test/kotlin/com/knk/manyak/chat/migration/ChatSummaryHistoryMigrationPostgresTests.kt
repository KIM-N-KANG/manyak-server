package com.knk.manyak.chat.migration

import com.knk.manyak.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatSummaryHistoryMigrationPostgresTests {
    private lateinit var jdbc: JdbcTemplate
    private var chat = 0L
    private var story = 0L
    @BeforeAll fun migrate() {
        val ds = SharedPostgres.database()
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("119").load().migrate()
        jdbc = JdbcTemplate(ds)
        story = jdbc.queryForObject("INSERT INTO stories(title) VALUES ('요약 이력 검증') RETURNING id", Long::class.java)!!
        chat = jdbc.queryForObject("INSERT INTO story_chats(story_id,summary,summary_through_turn) VALUES (?,'기존 요약',10) RETURNING id", Long::class.java, story)!!
        jdbc.update("INSERT INTO story_chats(story_id,summary,summary_through_turn) VALUES (?,'끝 턴 없음',0),(?,NULL,10)", story, story)
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
    }
    @Test fun `기존 유효 요약만 복사하고 구버전 컬럼과 삽입 호환을 보존한다`() {
        assertThat(jdbc.queryForObject("SELECT summary FROM story_chat_summaries WHERE chat_id=?", String::class.java, chat)).isEqualTo("기존 요약")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_chat_summaries WHERE summary IN ('기존 요약','끝 턴 없음')", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT summary_through_turn FROM story_chats WHERE id=?", Int::class.java, chat)).isEqualTo(10)
        assertThat(jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING revision", Int::class.java, story)).isZero()
        assertThatThrownBy { jdbc.update("UPDATE story_chats SET revision=-1 WHERE id=?", chat) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
    @Test fun `유효 요약은 끝 턴별 유일하고 무효화 이력은 보존하며 같은 끝 턴을 다시 저장한다`() {
        val id = jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING id", Long::class.java, story)!!
        jdbc.update("INSERT INTO story_chat_summaries(chat_id,through_turn,summary) VALUES (?,20,'첫 요약')", id)
        assertThatThrownBy { jdbc.update("INSERT INTO story_chat_summaries(chat_id,through_turn,summary) VALUES (?,20,'중복')", id) }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("UPDATE story_chat_summaries SET invalidated_at=now() WHERE chat_id=?", id)
        jdbc.update("INSERT INTO story_chat_summaries(chat_id,through_turn,summary) VALUES (?,20,'다시 요약')", id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_chat_summaries WHERE chat_id=?", Int::class.java, id)).isEqualTo(2)
        for (turn in listOf(0, -1)) assertThatThrownBy { jdbc.update("INSERT INTO story_chat_summaries(chat_id,through_turn,summary) VALUES (?,?,'범위 오류')", id, turn) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
