package com.knk.manyak.support

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 통합 테스트용 DB·Redis 초기화 헬퍼.
 *
 * 테스트 H2(jdbc:h2:mem:manyak-test)는 스프링 컨텍스트 간에 공유되므로, 각 테스트 클래스가
 * 자기 테이블만 골라 지우면 다른 클래스가 남긴 자식 행 때문에 실행 순서(전체/서브셋)에 따라
 * 부모 테이블 삭제가 FK 위반으로 실패한다. 테이블 목록을 information_schema에서 읽어
 * 참조 무결성을 잠시 끄고 전부 TRUNCATE하므로, 엔티티가 늘어도 정리 순서를 관리할 필요가 없다.
 *
 * 게스트 체험 한도 카운터(`guest_trial:*`, 스펙 §4-3-7)는 TTL이 없어(Phase 1 명시) 로컬 개발 Redis에
 * 그대로 누적된다. 같은 디바이스 id를 재사용하는 여러 테스트가 순서에 따라 한도를 소진해 서로
 * 간섭하지 않도록 DB 정리와 함께 지운다.
 */
@Component
class DatabaseCleaner(
    private val jdbcTemplate: JdbcTemplate,
    private val redisTemplate: StringRedisTemplate,
) {

    fun cleanAll() {
        val tables = jdbcTemplate.queryForList(
            // flyway_schema_history는 스키마 이력이지 테스트 데이터가 아니므로 비우지 않는다.
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = SCHEMA() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
            String::class.java,
        )
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE")
        try {
            tables.forEach { table -> jdbcTemplate.execute("""TRUNCATE TABLE "$table"""") }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE")
        }
        val guestTrialKeys = redisTemplate.keys("guest_trial:*")
        if (!guestTrialKeys.isNullOrEmpty()) {
            redisTemplate.delete(guestTrialKeys)
        }
    }
}
