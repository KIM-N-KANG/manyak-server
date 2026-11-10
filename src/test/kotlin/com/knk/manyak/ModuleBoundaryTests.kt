package com.knk.manyak

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 모듈 경계 위반을 세어 고정하는 측정기(KNK-2054). 운영 코드를 고치지 않고, 위반이 늘면 실패하고 줄면 기준을 내리게 한다.
 *
 * 모듈은 `com.knk.manyak.<x>` 최상위 패키지 하나다(루트의 `ManyakApplication`은 모듈 아님). ArchUnit으로 `src/main`
 * 바이트코드를 읽으므로 import 없이 정규화된 이름으로 쓴 참조도 잡는다. 의존은 클래스 쌍 단위로 세고, 중첩 클래스·동반 객체·
 * 람다(`Foo$Companion`, `Foo$1`)는 바깥 클래스로 접는다. 줄 번호·메서드 시그니처는 담지 않아 코드가 움직여도 줄이 바뀌지 않는다.
 *
 * 규칙(기준 파일은 `src/test/resources/module-boundary/`):
 * - **R1** `r1-repository-entity.txt`: 다른 모듈의 리포지토리·엔티티를 직접 참조하지 않는다. 대상이 `..repository..`·`..entity..`
 *   패키지에 있거나, `@Entity`·`@Embeddable`·`@MappedSuperclass`·`@Repository`가 붙었거나 Spring Data `Repository` 하위 타입이면
 *   위반이다(기능 하위 패키지의 `story.draft.StoryDraftRepository` 같은 것도 잡는다). 줄 = `참조하는 클래스 -> 참조되는 클래스`.
 *   admin도 예외가 아니다(여러 모듈의 데이터를 읽는 관리자 모듈이어도 다른 모듈의 리포지토리와 엔티티는 직접 참조하지 않는다.
 *   다른 모듈의 관리자 API가 함께 쓰는 감사 로그와 관리자 쿼리 도구는 admin이 아니라 adminkit에 있다).
 *   global 안의 엔티티·리포지토리도 대상이다(global 공용 예외는 R2의 x→global 간선에만 적용).
 * - **R2** `r2-cycle-edges.txt`: 모듈 간 순환을 만들지 않는다. 클래스 의존이 하나라도 있으면 모듈 간선 a→b로 보고, b에서 a로
 *   돌아오는 경로가 있는 간선을 위반으로 센다. 양방향 쌍만 세면 셋 이상이 도는 새 순환을 놓치므로 간선 단위로 고정한다.
 *   x→global 간선은 그래프에서 뺀다. 양방향 쌍 수(보고용)는 이 테스트 출력에 찍히고, 기준 파일에서 바로 뽑을 수도 있다:
 *   `awk '{e[$1" "$3]=1} END{for(k in e){split(k,p," "); if(p[1]<p[2] && ((p[2]" "p[1]) in e)) n++} print n+0}' src/test/resources/module-boundary/r2-cycle-edges.txt`
 * - **G** `g-global-to-domain.txt`: global은 모든 모듈이 쓰는 공용 모듈이라 도메인 모듈에 의존하지 않는다. 줄 = 클래스 쌍.
 *
 * 래칫은 **정확 일치**다. 현재 위반 집합이 기준 파일과 같아야 통과한다. 새 위반이면 실패하고, 고친 위반이 기준에 남아 있어도
 * 실패한다. 그래서 기준 파일은 항상 현재 상태이고 `cd src/test/resources/module-boundary && wc -l *.txt`가 규칙별 위반 수다
 * (그래서 기준 파일에 주석 줄을 넣지 않는다). 위반을 줄인 PR은 아래 갱신 명령으로 기준을 다시 쓰고 본문에 "R1 268 → 230"처럼 적는다.
 * 새 위반을 기준에 추가하는 것도 같은 명령이지만, 위반을 늘리지 않는 것이 원칙이며 늘린다면 PR에서 이유를 설명한다.
 *
 * `MODULE_BOUNDARY_UPDATE=true ./gradlew test --tests 'com.knk.manyak.ModuleBoundaryTests' --rerun`
 *
 * (환경 변수는 테스트 태스크 입력이 아니어서 `--rerun`이 필요하다.) R1 모듈 쌍별 집계:
 * `awk -F' -> ' '{split($1,a,".");split($2,b,"."); print a[4]" -> "b[4]}' src/test/resources/module-boundary/r1-repository-entity.txt | sort | uniq -c | sort -rn`
 */
class ModuleBoundaryTests {

    @Test
    fun `R1 다른 모듈의 리포지토리·엔티티를 직접 참조하지 않는다`() {
        val violations = crossModuleDependencies
            .filter { isRepositoryOrEntity(it.target) }
            .map { "${it.originName} -> ${outerName(it.target)}" }
            .toSet()

        assertMatchesBaseline(
            rule = "R1",
            file = "r1-repository-entity.txt",
            actual = violations,
            newViolationHint = "다른 모듈의 리포지토리·엔티티를 직접 참조했다. 그 모듈의 서비스(또는 서비스가 내주는 DTO·이벤트)를 통해 접근하세요.",
        )
    }

    @Test
    fun `R2 모듈 간 순환을 만들지 않는다`() {
        val edges = crossModuleDependencies.filter { it.targetModule != GLOBAL }.map { it.originModule to it.targetModule }.toSet()
        val cyclic = edges.filter { (from, to) -> from in reachableFrom(to, edges) }.toSet()
        val bidirectional = cyclic.filter { (a, b) -> a < b && (b to a) in cyclic }.map { (a, b) -> "$a <-> $b" }.sorted()
        println("R2 순환 간선 ${cyclic.size}개(모듈 간선 ${edges.size}개 중), 양방향 쌍 ${bidirectional.size}개: $bidirectional")

        assertMatchesBaseline(
            rule = "R2",
            file = "r2-cycle-edges.txt",
            actual = cyclic.map { (from, to) -> "$from -> $to" }.toSet(),
            newViolationHint = "모듈 간 순환을 만드는 의존이 생겼다(양방향 쌍 ${bidirectional.size}개: $bidirectional). " +
                "한쪽 방향으로만 의존하도록 바꾸세요(호출 방향을 뒤집거나 이벤트로 알리기).",
        )
    }

    @Test
    fun `G global은 도메인 모듈에 의존하지 않는다`() {
        val violations = crossModuleDependencies
            .filter { it.originModule == GLOBAL }
            .map { "${it.originName} -> ${outerName(it.target)}" }
            .toSet()

        assertMatchesBaseline(
            rule = "G",
            file = "g-global-to-domain.txt",
            actual = violations,
            newViolationHint = "global이 도메인 모듈을 참조했다. global은 모든 모듈이 의존하는 공용 모듈이라 도메인을 알면 순환이 생깁니다. " +
                "그 코드를 해당 도메인 모듈로 옮기거나, global에 인터페이스를 두고 도메인이 구현하게 하세요.",
        )
    }

    private fun assertMatchesBaseline(rule: String, file: String, actual: Set<String>, newViolationHint: String) {
        val baselineFile = File(BASELINE_DIR, file)
        if (System.getenv(UPDATE_ENV) == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(actual.sorted().joinToString("") { "$it\n" })
            println("$rule 갱신 모드: 기준 파일을 ${actual.size}줄로 덮어썼고 판정은 하지 않았다. $baselineFile")
            return
        }
        val baseline = if (baselineFile.exists()) baselineFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
        val added = (actual - baseline).sorted()
        val resolved = (baseline - actual).sorted()
        if (added.isEmpty() && resolved.isEmpty()) return

        fail<Unit>(
            buildString {
                appendLine("$rule 위반 ${actual.size} (기준 ${baseline.size}): 추가 ${added.size}, 해소 ${resolved.size}. 기준 파일: $baselineFile")
                if (added.isNotEmpty()) {
                    appendLine()
                    appendLine("[새 위반] $newViolationHint")
                    appendLine("정말 필요하면 아래 갱신 명령으로 기준 파일에 추가하고, PR 본문에 왜 필요한지 설명하세요.")
                    added.forEach { appendLine("  + $it") }
                }
                if (resolved.isNotEmpty()) {
                    appendLine()
                    appendLine("[해소] 위반이 줄었습니다. 아래 갱신 명령을 돌려 기준 파일에서 지우고, PR 본문에 '$rule ${baseline.size} → ${actual.size}'을 적으세요.")
                    resolved.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("갱신 명령: $UPDATE_COMMAND")
            },
        )
    }

    private data class Dependency(val originModule: String, val originName: String, val targetModule: String, val target: JavaClass)

    private companion object {
        const val ROOT = "com.knk.manyak"
        const val GLOBAL = "global"
        const val BASELINE_DIR = "src/test/resources/module-boundary"
        const val UPDATE_ENV = "MODULE_BOUNDARY_UPDATE"
        const val UPDATE_COMMAND = "$UPDATE_ENV=true ./gradlew test --tests 'com.knk.manyak.ModuleBoundaryTests' --rerun"

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
