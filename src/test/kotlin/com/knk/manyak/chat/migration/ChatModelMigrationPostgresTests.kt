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

/** V104의 실제 채팅을 만든 뒤 V105를 적용한다. H2 ddl-auto로 검증할 수 없는 경로다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatModelMigrationPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "chat-model-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private var chatId = 0L

    @BeforeAll fun migrate() {
        pg.start()
        val datasource = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "chat-model-test")
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("104").load().migrate()
        jdbc = JdbcTemplate(datasource)
        val story = jdbc.queryForObject("INSERT INTO stories(title) VALUES ('기존 채팅 검증') RETURNING id", Long::class.java)!!
        chatId = jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING id", Long::class.java, story)!!
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate()
    }
    @AfterAll fun stop() { pg.stop() }

    @Test fun `V105 적용 뒤 구버전과 롤백 이미지의 컬럼 생략 INSERT도 LITE로 저장된다`() {
        val story = jdbc.queryForObject("SELECT story_id FROM story_chats WHERE id = ?", Long::class.java, chatId)!!
        repeat(2) {
            val model = jdbc.queryForObject("INSERT INTO story_chats(story_id) VALUES (?) RETURNING model_code", String::class.java, story)
            assertThat(model).isEqualTo("LITE")
        }
    }

    @Test fun `기존 채팅은 LITE로 채워지고 새 모델의 가격과 기본 여부가 시드된다`() {
        assertThat(jdbc.queryForObject("SELECT model_code FROM story_chats WHERE id = ?", String::class.java, chatId)).isEqualTo("LITE")
        assertThat(jdbc.queryForList("SELECT turn_cost FROM chat_models ORDER BY sort_order", Long::class.java)).containsExactly(20L, 50L)
        assertThat(jdbc.queryForObject("SELECT code FROM chat_models WHERE is_default", String::class.java)).isEqualTo("AURORA")

    }

    @Test fun `모델 가격 범위와 활성 기본 모델 유니크와 모델 외래키를 PostgreSQL에서 강제한다`() {
        for (sql in listOf(
            "UPDATE chat_models SET turn_cost = 0 WHERE code = 'LITE'",
            "UPDATE chat_models SET turn_cost = 10001 WHERE code = 'LITE'",
            "UPDATE chat_models SET sort_order = 0 WHERE code = 'LITE'",
            "UPDATE chat_models SET is_default = true WHERE code = 'LITE'",
            "UPDATE chat_models SET is_active = false WHERE code = 'AURORA'",
            "UPDATE story_chats SET model_code = 'UNKNOWN' WHERE id = $chatId",
            "UPDATE story_chats SET model_code = NULL WHERE id = $chatId",
        )) assertThatThrownBy { jdbc.update(sql) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test fun `차감 연결은 nullable이고 각 테이블에서 중복 연결을 거절한다`() {
        val user = jdbc.queryForObject("INSERT INTO users(nickname) VALUES ('마이그레이션회원') RETURNING id", Long::class.java)!!
        val charge = jdbc.queryForObject("INSERT INTO credit_transactions(user_id,amount,reason) VALUES (?,-50,'CHAT_TURN') RETURNING id", Long::class.java, user)!!
        val message = jdbc.queryForObject("INSERT INTO story_messages(chat_id,role,content,message_order,credit_transaction_id) VALUES (?,'ASSISTANT','검증',2,?) RETURNING id", Long::class.java, chatId, charge)!!
        assertThatThrownBy {
            jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order,credit_transaction_id) VALUES (?,'ASSISTANT','중복',4,?)", chatId, charge)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("INSERT INTO story_message_versions(message_id,version_number,content,choices,credit_transaction_id) VALUES (?,1,'검증','[]',?)", message, charge)
        assertThatThrownBy {
            jdbc.update("INSERT INTO story_message_versions(message_id,version_number,content,choices,credit_transaction_id) VALUES (?,2,'중복','[]',?)", message, charge)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        jdbc.update("INSERT INTO story_messages(chat_id,role,content,message_order) VALUES (?,'USER','무료',5),(?,'ASSISTANT','무료',6)", chatId, chatId)
    }
}
