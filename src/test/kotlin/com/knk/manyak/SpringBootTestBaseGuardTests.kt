package com.knk.manyak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 통합 테스트가 `@SpringBootTest`를 직접 달지 않고 공용 베이스를 상속하는지 지키는 회귀 가드(KNK-2053).
 *
 * Spring 테스트 컨텍스트 캐시 키는 클래스에 붙은 설정을 모두 합친 값이다. 클래스마다 `@SpringBootTest`와 속성·목·설정을
 * 따로 달면 키가 클래스 수만큼 갈려 컨텍스트가 다시 뜨고, 캐시 상한(32)을 넘으면 축출·재기동까지 생긴다(정리 전 125회 기동).
 * 그래서 `@SpringBootTest`는 `support/IntegrationTest`에만 두고, 통합 테스트는 그 클래스나 하위 베이스를 상속한다.
 *
 * 허용 목록은 **줄일 때만 갱신하는 래칫**이다([SpringContextBudgetGuardTests]와 같은 규칙).
 * - `TestDatasourceIsolationGuardTests`: 프로파일을 일부러 달지 않은 상태의 격리를 검증하므로 베이스를 쓸 수 없다.
 * - `StoryTakedownPostgresTests`: Testcontainers PostgreSQL에 붙는 컨텍스트라 H2 베이스와 합칠 수 없다.
 * 새 통합 테스트에 다른 설정이 필요하면 베이스를 늘리기 전에 공용 스파이·교체형 페이크로 풀 수 있는지 먼저 본다.
 */
class SpringBootTestBaseGuardTests {

    @Test
    fun `SpringBootTest를 직접 다는 클래스는 공용 베이스와 허용 목록뿐이다`() {
        val direct = File(TEST_SOURCE_ROOT).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { SPRING_BOOT_TEST.containsMatchIn(it) } }
            .map { it.invariantSeparatorsPath.removePrefix("$TEST_SOURCE_ROOT/") }
            .toSet()

        assertThat(direct)
            .`as`(
                "@SpringBootTest를 직접 단 파일이 허용 목록 밖에 있다. 클래스마다 컨텍스트가 하나씩 늘어난다. " +
                    "support/IntegrationTest(또는 하위 베이스)를 상속하세요.",
            )
            .isSubsetOf(ALLOWED)
    }

    private companion object {
        const val TEST_SOURCE_ROOT = "src/test/kotlin"

        // 들여쓰기 없는 클래스 어노테이션만 센다. 정규화된 이름(@org...SpringBootTest)도 잡는다.
        val SPRING_BOOT_TEST = Regex("""^@(\w+\.)*SpringBootTest\b""")

        val ALLOWED = setOf(
            "com/knk/manyak/support/IntegrationTest.kt",
            "com/knk/manyak/TestDatasourceIsolationGuardTests.kt",
            "com/knk/manyak/story/submission/StoryTakedownPostgresTests.kt",
        )
    }
}
