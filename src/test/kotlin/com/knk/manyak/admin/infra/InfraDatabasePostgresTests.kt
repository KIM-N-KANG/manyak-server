package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

class InfraDatabasePostgresTests {
    @Test fun `실제 PostgreSQL에서 사용자 테이블 크기 상위와 인덱스 포함 크기 및 행 추정치를 조회한다`() {
        GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD", "infra-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)).use { pg ->
                pg.start()
                val ds = DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres", "postgres", "infra-test")
                val jdbc = JdbcTemplate(ds)
                jdbc.execute("CREATE SCHEMA infra_test")
                jdbc.execute("CREATE TABLE infra_test.\"big table\"(id INT PRIMARY KEY, payload TEXT)")
                jdbc.execute("INSERT INTO infra_test.\"big table\" SELECT x, repeat(md5(x::text), 10) FROM generate_series(1,10000) x")
                jdbc.execute("CREATE TABLE public.small_table(id INT)")
                jdbc.execute("ANALYZE infra_test.\"big table\"")
                val repo = InfraDatabaseRepository(NamedParameterJdbcTemplate(ds))
                val rows = repo.largestTables(1)
                assertThat(rows).hasSize(1)
                val row = rows.single()
                assertThat(row.schema).isEqualTo("infra_test"); assertThat(row.table).isEqualTo("big table")
                assertThat(row.totalBytes).isEqualTo(row.tableBytes + row.indexBytes)
                assertThat(row.indexBytes).isPositive(); assertThat(row.estimatedRows).isEqualTo(10000)
                assertThat(repo.largestTables(100).map { it.schema }).doesNotContain("pg_catalog", "information_schema")
                assertThatThrownBy { repo.largestTables(0) }.isInstanceOf(IllegalArgumentException::class.java)
            }
    }
}
