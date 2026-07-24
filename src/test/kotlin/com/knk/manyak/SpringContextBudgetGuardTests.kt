package com.knk.manyak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 통합 테스트가 만드는 Spring 컨텍스트 수를 간접적으로 묶는 회귀 가드(KNK-686).
 *
 * 테스트 클래스 안에 중첩한 `@TestConfiguration`은 그 클래스만의 설정 키가 되어 **컨텍스트를 하나 더** 만든다.
 * 컨텍스트가 Spring 캐시 상한(32)을 넘으면 축출이 일어나고, 축출은 컨텍스트를 닫고 다시 띄우는 일이라
 * 두 가지 사고로 이어졌다: CI에서 러너가 재기동에 점유돼 무관한 테스트가 ReadTimeout으로 끊겼고,
 * 로컬에서는 닫히는 컨텍스트의 Hibernate DROP이 공유 H2 스키마를 떨어뜨렸다.
 *
 * 그래서 중첩 설정 개수에 상한을 건다. 최상위 설정(들여쓰기 0)은 `@Import`로 여러 클래스가 공유할 수 있어
 * 컨텍스트를 늘리지 않으므로 세지 않는다. 컨텍스트 수 자체를 세는 공개 API는 없고 실행 순서에 의존하므로,
 * 사고의 실제 원인인 이 숫자를 대리 지표로 쓴다.
 *
 * 상한은 **줄일 때만 갱신하는 래칫**이다. 새 통합 테스트에 설정이 필요하면 클래스 안에 중첩하지 말고,
 * 최상위 `@TestConfiguration`을 만들어 여러 클래스가 `@Import`로 공유한다(그러면 설정 키가 같아 컨텍스트가
 * 하나로 합쳐진다). 동작을 테스트마다 바꿔야 하면 토글 가능한 페이크 빈을 두고 `@AfterEach`에서 원복한다.
 */
class SpringContextBudgetGuardTests {

    @Test
    fun `중첩 @TestConfiguration 개수는 상한을 넘지 않는다`() {
        val nested = File(TEST_SOURCE_ROOT).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it != it.trimStart() && NESTED_TEST_CONFIGURATION.containsMatchIn(it.trim()) }
                    .map { file.path }
            }
            .toList()

        assertThat(nested.size)
            .`as`(
                "중첩 @TestConfiguration이 %d개다(상한 %d). 클래스마다 컨텍스트가 하나씩 늘어 캐시 상한(32)을 넘기면 " +
                    "축출이 시작된다. 최상위 설정을 만들어 @Import로 공유하세요.\n%s",
                nested.size,
                MAX_NESTED_TEST_CONFIGURATIONS,
                nested.joinToString("\n"),
            )
            .isLessThanOrEqualTo(MAX_NESTED_TEST_CONFIGURATIONS)
    }

    private companion object {
        const val TEST_SOURCE_ROOT = "src/test/kotlin"

        // 인자 있는 형태(@TestConfiguration(proxyBeanMethods = false))와 정규화된 이름도 센다.
        // 정확히 "@TestConfiguration"인 줄만 세면 그 변형들이 래칫을 그대로 통과해, 막으려던 것과 똑같이
        // 클래스마다 컨텍스트가 늘어난다(Codex 리뷰 지적).
        val NESTED_TEST_CONFIGURATION = Regex("""^@(\w+\.)*TestConfiguration\b""")

        // 현재 값(17)으로 고정한다. 더 줄였을 때만 이 숫자를 내린다.
        const val MAX_NESTED_TEST_CONFIGURATIONS = 17
    }
}
