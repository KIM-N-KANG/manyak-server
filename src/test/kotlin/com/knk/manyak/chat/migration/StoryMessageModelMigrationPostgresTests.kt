package com.knk.manyak.chat.migration

import com.knk.manyak.admin.analytics.AdminMetricReadRepository
import com.knk.manyak.admin.analytics.AnalyticsRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.LocalDate

/** V109의 기존 메시지를 보존한 채 응답 모델 컬럼을 추가한다. H2 ddl-auto와 별도로 FK와 NULL을 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoryMessageModelMigrationPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "message-model-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var datasource: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private var chatId = 0L

    @BeforeAll fun migrate() {
        pg.start()
        datasource = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "message-model-test")
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("109").load().migrate()
        jdbc = JdbcTemplate(datasource)
        val story = jdbc.queryForObject("INSERT INTO stories(title) VALUES ('응답 모델 검증') RETURNING id", Long::class.java)!!
        chatId = jdbc.queryForObject("INSERT INTO story_chats(story_id,model_code) VALUES (?,'AURORA') RETURNING id", Long::class.java, story)!!
        jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'USER','기존 입력',1),(?,'ASSISTANT','기존 응답',2)", chatId, chatId)
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate()
    }

    @AfterAll fun stop() { pg.stop() }

    @Test fun `응답 모델은 varchar20 nullable이며 기본값 없이 기존 행을 백필하지 않는다`() {
        val column = jdbc.queryForMap("""SELECT data_type, character_maximum_length, is_nullable, column_default
            FROM information_schema.columns WHERE table_schema='public' AND table_name='story_messages' AND column_name='model_code'""")
        assertThat(column["data_type"]).isEqualTo("character varying")
        assertThat(column["character_maximum_length"]).isEqualTo(20)
        assertThat(column["is_nullable"]).isEqualTo("YES")
        assertThat(column["column_default"]).isNull()
        assertThat(jdbc.queryForList("SELECT model_code FROM story_messages WHERE chat_id=? AND message_order<=2", String::class.java, chatId))
            .hasSize(2).containsOnlyNulls()
        assertThat(jdbc.queryForObject("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'ASSISTANT','구버전 삽입',3) RETURNING model_code", String::class.java, chatId)).isNull()
        assertThat(jdbc.queryForObject("INSERT INTO story_messages(chat_id,role,content,message_order,model_code) VALUES (?,'USER','명시적 NULL',4,NULL) RETURNING model_code", String::class.java, chatId)).isNull()
        // 재실행은 적용된 마이그레이션을 다시 실행하거나 기존 행을 채우지 않는다.
        assertThat(Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate().migrationsExecuted).isZero()
    }

    @Test fun `응답 청구 모델 FK는 등록 모델을 허용하고 없는 모델의 삽입과 갱신을 거절한다`() {
        val message = jdbc.queryForObject("INSERT INTO story_messages(chat_id,role,content,message_order,model_code) VALUES (?,'ASSISTANT','청구 모델',10,'LITE') RETURNING id", Long::class.java, chatId)!!
        jdbc.update("UPDATE story_messages SET model_code='AURORA' WHERE id=?", message)
        assertThat(jdbc.queryForObject("SELECT model_code FROM story_messages WHERE id=?", String::class.java, message)).isEqualTo("AURORA")
        assertThatThrownBy { jdbc.update("UPDATE story_messages SET model_code='UNKNOWN' WHERE id=?", message) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,model_code) VALUES (?,'ASSISTANT','잘못된 모델',11,'UNKNOWN')", chatId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("UPDATE story_messages SET model_code=NULL WHERE id=?", message)
        assertThat(jdbc.queryForObject("SELECT model_code FROM story_messages WHERE id=?", String::class.java, message)).isNull()
    }

    @Test fun `PostgreSQL 집계는 채팅 모델 대신 응답 청구 모델과 UNRECORDED를 KST 날짜로 묶는다`() {
        jdbc.update("""INSERT INTO story_messages(chat_id,role,content,message_order,model_code,created_at) VALUES
            (?,'ASSISTANT','기록 전',20,NULL,'2026-10-20T15:00:00Z'),
            (?,'ASSISTANT','게스트 LITE',21,'LITE','2026-10-21T14:59:59.999Z'),
            (?,'ASSISTANT','AURORA',22,'AURORA','2026-10-20T15:00:00Z'),
            (?,'USER','입력 제외',23,NULL,'2026-10-20T15:00:00Z'),
            (?,'ASSISTANT','종료 경계 제외',24,'LITE','2026-10-21T15:00:00Z')""", chatId, chatId, chatId, chatId, chatId)
        val rows = AdminMetricReadRepository(NamedParameterJdbcTemplate(datasource)).turnsByModel(
            AnalyticsRange(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-21")), emptyList())
        assertThat(rows.associate { it["model"] to (it["turns"] as Number).toLong() })
            .isEqualTo(mapOf("AURORA" to 1L, "LITE" to 1L, "UNRECORDED" to 1L))
    }
}
