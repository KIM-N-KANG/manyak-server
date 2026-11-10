package com.knk.manyak.support

import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 실제 PostgreSQL 테스트가 공유하는 JVM 싱글턴 컨테이너(KNK-2058).
 *
 * 예전에는 클래스마다 컨테이너를 띄워 기동만 24회 반복했다. 이제 이미지별 컨테이너는 JVM당 한 번만 띄우고
 * (종료는 Testcontainers Ryuk), 격리는 클래스마다 `CREATE DATABASE`로 새 DB를 주는 것으로 지킨다.
 * - `migrated = false`: 빈 DB. 특정 버전까지 올린 뒤 다음 버전을 적용하는 마이그레이션 테스트와 손으로 만든 스키마용.
 * - `migrated = true`: Flyway 전체 migrate를 한 번 마친 템플릿 DB를 복제한다(마이그레이션 SQL은 JVM당 1회).
 * `flyway.clean()`을 쓰지 않는 이유: Spring 컨텍스트 캐시에 남은 DB를 다른 클래스가 지우면 깨지고,
 * DB 전체 통계를 단언하는 테스트는 빈 DB가 필요하다.
 * 앱 테스트의 H2 격리(`build.gradle.kts`의 실 DB env 차단)와 무관하게 컨테이너 매핑 포트에만 붙는다.
 */
object SharedPostgres {
    const val POSTGRES_16 = "postgres:16-alpine"
    const val POSTGRES_17 = "postgres:17-alpine"
    private const val PASSWORD = "shared-postgres-test"
    private const val TEMPLATE = "manyak_template"
    private val containers = ConcurrentHashMap<String, GenericContainer<*>>()
    private val templates = ConcurrentHashMap<String, String>()

    /** 호출한 테스트 전용 새 DB의 datasource. */
    fun database(image: String = POSTGRES_16, migrated: Boolean = false): DriverManagerDataSource {
        val container = container(image)
        val template = if (migrated) template(image) else "template1"
        val name = "t_" + UUID.randomUUID().toString().replace("-", "")
        admin(container).execute("CREATE DATABASE $name TEMPLATE $template")
        return dataSource(container, name)
    }

    private fun container(image: String) = containers.computeIfAbsent(image) {
        GenericContainer(image).withEnv("POSTGRES_PASSWORD", PASSWORD).withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
            .apply { start() }
    }

    private fun template(image: String) = templates.computeIfAbsent(image) {
        val container = container(image)
        val admin = admin(container)
        admin.execute("CREATE DATABASE $TEMPLATE")
        Flyway.configure().dataSource(dataSource(container, TEMPLATE)).locations("classpath:db/migration").load().migrate()
        // 템플릿 복제는 원본에 접속한 세션이 있으면 실패한다. 닫힌 연결의 백엔드가 내려갈 때까지 기다린다.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = ?", Long::class.java, TEMPLATE)!! > 0) {
            check(System.nanoTime() < deadline) { "템플릿 DB 연결이 닫히지 않았다" }
            Thread.sleep(10)
        }
        TEMPLATE
    }

    private fun admin(container: GenericContainer<*>) = JdbcTemplate(dataSource(container, "postgres"))

    private fun dataSource(container: GenericContainer<*>, database: String) =
        DriverManagerDataSource("jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$database", "postgres", PASSWORD)
}
