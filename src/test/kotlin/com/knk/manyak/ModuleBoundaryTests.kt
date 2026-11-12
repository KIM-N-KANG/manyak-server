package com.knk.manyak

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * 모듈 경계 가드(KNK-2054, KNK-2080). 아래 세 규칙의 위반이 하나라도 생기면 실패하고 위반 줄을 그대로 출력한다.
 *
 * 모듈은 `com.knk.manyak.<x>` 최상위 패키지 하나다(루트의 `ManyakApplication`은 모듈 아님). ArchUnit으로 `src/main`
 * 바이트코드를 읽으므로 import 없이 정규화된 이름으로 쓴 참조도 잡는다. 의존은 클래스 쌍 단위로 세고, 중첩 클래스·동반 객체·
 * 람다(`Foo$Companion`, `Foo$1`)는 바깥 클래스로 접는다. 줄 번호·메서드 시그니처는 담지 않아 코드가 움직여도 줄이 바뀌지 않는다.
 *
 * 규칙(모두 위반이 비어 있어야 한다, BE-061):
 * - **R1**: 다른 모듈의 리포지토리·엔티티를 직접 참조하지 않는다. 대상이 `..repository..`·`..entity..`
 *   패키지에 있거나, `@Entity`·`@Embeddable`·`@MappedSuperclass`·`@Repository`가 붙었거나 Spring Data `Repository` 하위 타입이면
 *   위반이다(기능 하위 패키지의 `story.draft.StoryDraftRepository` 같은 것도 잡는다). 줄 = `참조하는 클래스 -> 참조되는 클래스`.
 *   admin도 예외가 아니다(여러 모듈의 데이터를 읽는 관리자 모듈이어도 다른 모듈의 리포지토리와 엔티티는 직접 참조하지 않는다.
 *   다른 모듈의 관리자 API가 함께 쓰는 감사 로그와 관리자 쿼리 도구는 admin이 아니라 adminkit에 있다).
 *   global 안의 엔티티·리포지토리도 대상이다(global 공용 예외는 R2의 x→global 간선에만 적용).
 * - **R2**: 모듈 간 순환을 만들지 않는다. 클래스 의존이 하나라도 있으면 모듈 간선 a→b로 보고, b에서 a로
 *   돌아오는 경로가 있는 간선을 위반으로 센다. 양방향 쌍만 세면 셋 이상이 도는 새 순환을 놓치므로 간선 단위로 본다.
 *   x→global 간선은 그래프에서 뺀다.
 * - **G**: global은 모든 모듈이 쓰는 공용 모듈이라 도메인 모듈에 의존하지 않는다. 줄 = 클래스 쌍.
 *
 * 기준 파일과 갱신 모드는 없다(KNK-2080에서 세 규칙 모두 0이 되어 정확 일치 래칫을 "비어 있어야 한다"로 바꿨다).
 */
class ModuleBoundaryTests {

    @Test
    fun `R1 다른 모듈의 리포지토리·엔티티를 직접 참조하지 않는다`() {
        val violations = crossModuleDependencies
            .filter { isRepositoryOrEntity(it.target) }
            .map { "${it.originName} -> ${outerName(it.target)}" }
            .toSet()

        assertNoViolations(
            rule = "R1",
            violations = violations,
            hint = "다른 모듈의 리포지토리·엔티티를 직접 참조했다. 그 모듈의 서비스(또는 서비스가 내주는 DTO·이벤트)를 통해 접근하세요.",
        )
    }

    @Test
    fun `R2 모듈 간 순환을 만들지 않는다`() {
        val edges = crossModuleDependencies.filter { it.targetModule != GLOBAL }.map { it.originModule to it.targetModule }.toSet()
        val cyclic = edges.filter { (from, to) -> from in reachableFrom(to, edges) }.toSet()
        val bidirectional = cyclic.filter { (a, b) -> a < b && (b to a) in cyclic }.map { (a, b) -> "$a <-> $b" }.sorted()
        println("R2 순환 간선 ${cyclic.size}개(모듈 간선 ${edges.size}개 중), 양방향 쌍 ${bidirectional.size}개: $bidirectional")

        assertNoViolations(
            rule = "R2",
            violations = cyclic.map { (from, to) -> "$from -> $to" }.toSet(),
            hint = "모듈 간 순환을 만드는 의존이 생겼다(양방향 쌍 ${bidirectional.size}개: $bidirectional). " +
                "한쪽 방향으로만 의존하도록 바꾸세요(호출 방향을 뒤집거나 이벤트로 알리기).",
        )
    }

    @Test
    fun `G global은 도메인 모듈에 의존하지 않는다`() {
        val violations = crossModuleDependencies
            .filter { it.originModule == GLOBAL }
            .map { "${it.originName} -> ${outerName(it.target)}" }
            .toSet()

        assertNoViolations(
            rule = "G",
            violations = violations,
            hint = "global이 도메인 모듈을 참조했다. global은 모든 모듈이 의존하는 공용 모듈이라 도메인을 알면 순환이 생깁니다. " +
                "그 코드를 해당 도메인 모듈로 옮기거나, global에 인터페이스를 두고 도메인이 구현하게 하세요.",
        )
    }

    private fun assertNoViolations(rule: String, violations: Set<String>, hint: String) {
        if (violations.isEmpty()) return
        fail<Unit>(
            buildString {
                appendLine("$rule 위반 ${violations.size}건. $hint")
                violations.sorted().forEach { appendLine("  $it") }
            },
        )
    }

    private data class Dependency(val originModule: String, val originName: String, val targetModule: String, val target: JavaClass)

    private companion object {
        const val ROOT = "com.knk.manyak"
        const val GLOBAL = "global"

        val INTERNAL_PACKAGE = Regex("""\.(repository|entity)(\.|$)""")
        val INTERNAL_ANNOTATIONS = listOf(
            "jakarta.persistence.Entity",
            "jakarta.persistence.Embeddable",
            "jakarta.persistence.MappedSuperclass",
            "org.springframework.stereotype.Repository",
        )

        // 임포트(약 3초)는 세 테스트가 공유한다.
        val crossModuleDependencies: List<Dependency> by lazy {
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(ROOT)
                .flatMap { it.directDependenciesFromSelf }
                .mapNotNull { dependency ->
                    val origin = moduleOf(dependency.originClass) ?: return@mapNotNull null
                    val target = moduleOf(dependency.targetClass) ?: return@mapNotNull null
                    if (origin == target) null else Dependency(origin, outerName(dependency.originClass), target, dependency.targetClass)
                }
        }

        fun moduleOf(javaClass: JavaClass): String? =
            javaClass.packageName.takeIf { it.startsWith("$ROOT.") }?.removePrefix("$ROOT.")?.substringBefore('.')

        fun outerName(javaClass: JavaClass): String = javaClass.name.substringBefore('$')

        fun isRepositoryOrEntity(javaClass: JavaClass): Boolean {
            if (INTERNAL_PACKAGE.containsMatchIn(javaClass.packageName)) return true
            val outer = generateSequence(javaClass) { it.enclosingClass.orElse(null) }.last()
            return listOf(javaClass, outer).any { candidate ->
                INTERNAL_ANNOTATIONS.any(candidate::isAnnotatedWith) ||
                    candidate.isAssignableTo("org.springframework.data.repository.Repository")
            }
        }

        fun reachableFrom(start: String, edges: Set<Pair<String, String>>): Set<String> {
            val seen = mutableSetOf<String>()
            val pending = ArrayDeque(listOf(start))
            while (pending.isNotEmpty()) {
                val module = pending.removeLast()
                edges.filter { it.first == module }.forEach { if (seen.add(it.second)) pending.add(it.second) }
            }
            return seen
        }
    }
}
