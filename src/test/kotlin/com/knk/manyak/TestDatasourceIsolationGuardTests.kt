package com.knk.manyak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

/**
 * 테스트 격리 회귀 가드.
 *
 * 이 클래스는 의도적으로 @ActiveProfiles("test")를 달지 않는다. 프로파일을 명시하지 않은 통합 테스트가
 * 부팅될 때 기본 프로파일이 `test`(H2, Flyway off)로 강제되는지 — 즉 build.gradle.kts의 안전 기본값
 * (spring.profiles.active=test)이 살아 있는지 — 를 검증한다.
 *
 * 이 가드가 사라지면 기본 프로파일은 application.yml의 `local`이 되어 datasource가
 * ${MANYAK_DB_URL}(dev/local Postgres)로 해석되고 Flyway가 실 DB에 돈다. 개발자 셸에 MANYAK_DB_*가
 * export돼 있으면 `./gradlew test` 한 번이 실 DB에 마이그레이션을 적용하는 격리 유출이 된다. 그 회귀를 여기서 잡는다.
 *
 * Gradle test 태스크가 시스템 프로퍼티를 주입하므로 반드시 Gradle로 실행한다(IntelliJ 기본값도 Gradle 위임).
 */
@SpringBootTest
class TestDatasourceIsolationGuardTests {

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `프로파일을 지정하지 않은 통합 테스트도 격리된 H2 test 프로파일로 부팅된다`() {
        assertThat(environment.activeProfiles)
            .`as`("기본 활성 프로파일은 test여야 한다(누락 시 local→실 DB로 유출).")
            .contains("test")
        assertThat(environment.getProperty("spring.datasource.url"))
            .`as`("테스트 datasource는 격리된 H2여야 한다.")
            .startsWith("jdbc:h2:mem:")
        assertThat(environment.getProperty("spring.flyway.enabled"))
            .`as`("테스트에서 Flyway는 꺼져 있어야 한다(실 DB 마이그레이션 방지).")
            .isEqualTo("false")
    }
}
