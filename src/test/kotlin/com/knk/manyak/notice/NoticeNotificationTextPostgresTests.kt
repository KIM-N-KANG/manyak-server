package com.knk.manyak.notice

import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.sql.SQLException
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoticeNotificationTextPostgresTests {
    private val pg = GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "notice-text-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    private lateinit var jdbc: JdbcTemplate
    private val oldId = UUID.randomUUID()
    @BeforeAll fun start() {
        pg.start()
        val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "notice-text-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("111").load().migrate()
        jdbc = JdbcTemplate(ds)
        val owner = jdbc.queryForObject("INSERT INTO users(public_id,nickname) VALUES (?,'관리자') RETURNING id", Long::class.java, UUID.randomUUID())!!
        jdbc.update("""INSERT INTO notices(public_id,title,body,tag,created_by_user_id,updated_at)
            VALUES (?,'기존 제목','기존 본문','SERVICE',?,CURRENT_TIMESTAMP)""", oldId, owner)
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
    }
    @AfterAll fun stop() { pg.stop() }
    @Test fun `V112는 문구 두 열을 nullable 길이 100 300과 기본값 없이 추가한다`() {
        val rows = jdbc.queryForList("""SELECT column_name,is_nullable,character_maximum_length,column_default FROM information_schema.columns
            WHERE table_name='notices' AND column_name IN ('notification_title','notification_body') ORDER BY column_name""")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it["is_nullable"] }).containsOnly("YES")
        assertThat(rows.map { it["column_default"] }).containsOnlyNulls()
        assertThat(rows.map { (it["character_maximum_length"] as Number).toInt() }).containsExactly(300,100)
    }
    @Test fun `V112는 기존 공지의 문구를 채우지 않는다`() {
        val row = jdbc.queryForMap("SELECT notification_title,notification_body FROM notices WHERE public_id=?", oldId)
        assertThat(row["notification_title"]).isNull(); assertThat(row["notification_body"]).isNull()
    }
    @Test fun `두 문구는 함께 null이거나 함께 값이어야 한다`() {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO notices(public_id,title,body,tag,created_by_user_id,updated_at)
            SELECT ?,'제약 확인','본문','SERVICE',created_by_user_id,CURRENT_TIMESTAMP FROM notices WHERE public_id=?""", id, oldId)
        for (values in listOf("notification_title='제목'", "notification_body='본문'")) {
            val failure = catchThrowable { jdbc.update("UPDATE notices SET $values WHERE public_id=?", id) }
            assertThat(failure).isNotNull()
            val cause = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().first()
            assertThat(cause.sqlState).isEqualTo("23514")
        }
        assertThat(jdbc.update("UPDATE notices SET notification_title='제목',notification_body='본문' WHERE public_id=?", id)).isEqualTo(1)
        assertThat(jdbc.update("UPDATE notices SET notification_title=NULL,notification_body=NULL WHERE public_id=?", id)).isEqualTo(1)
    }
}
