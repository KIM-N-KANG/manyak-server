package com.knk.manyak.story.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * KNK-847 제작 태그 마스터 개편(V57) 검증.
 *
 * 테스트 프로파일은 Flyway 비활성 + H2 ddl-auto라 마이그레이션이 실행되지 않는다(운영 PostgreSQL만 Flyway로 적재).
 * 그래서 [DefaultTagSeedMigrationTests]와 같은 방식으로 V57 SQL을 직접 파싱해 최종 목록(40)의 카테고리별 개수·정렬 순서·
 * sort_order(10 단위)와 비활성화 대상 개수(32)를 결정적으로 검증한다. 실 DB 적용·비활성 제외는 scripts/gen-db-docs.sh로 본다.
 */
class RevampTagMasterMigrationTests {

    private val migrationSql: String =
        requireNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/V57__revamp_story_creation_tags.sql"),
        ) { "V57__revamp_story_creation_tags.sql 마이그레이션을 찾을 수 없습니다." }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private val expectedGenres = listOf(
        "로맨스 판타지", "현대 판타지", "로맨스", "무협", "헌터", "학원", "중세 판타지", "재벌", "게임",
        "아포칼립스", "생존", "BL", "SF", "요리", "탈출",
    )

    private val expectedProtagonists = listOf(
        "회귀", "빙의", "환생", "먼치킨", "천재", "성장형", "시스템", "츤데레", "다정한", "트라우마",
        "정의로운", "책임감", "계획적인", "시한부", "아이돌",
    )

    private val expectedSupporting = listOf(
        "집착하는", "츤데레", "다정한", "소꿉친구", "짝사랑", "연인", "라이벌", "사랑스러운", "가족", "천재",
    )

    private data class FinalTag(val category: String, val name: String, val sortOrder: Int)

    /** 6단계 최종 목록 블록 `FROM (VALUES ... ) AS v(tag_type, name, sort_order)`에서 (category, name, sort_order)를 순서대로 뽑는다. */
    private fun finalTags(): List<FinalTag> {
        val block = requireNotNull(
            Regex("""FROM \(VALUES([\s\S]*?)\) AS v\(tag_type, name, sort_order\)""").find(migrationSql),
        ) { "6단계 최종 목록 VALUES 블록을 찾을 수 없습니다." }.groupValues[1]
        val row = Regex("""\(\s*'(GENRE|PROTAGONIST|SUPPORTING_CHARACTER)'\s*,\s*'([^']+)'\s*,\s*(\d+)\s*\)""")
        return row.findAll(block).map { FinalTag(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }.toList()
    }

    private fun finalTags(category: String): List<FinalTag> = finalTags().filter { it.category == category }

    @Test
    fun `최종 장르 목록은 15개이며 티켓 순서와 일치한다`() {
        assertEquals(expectedGenres, finalTags("GENRE").map { it.name })
        assertEquals(15, finalTags("GENRE").size)
    }

    @Test
    fun `최종 주인공 특징 목록은 15개이며 티켓 순서와 일치한다`() {
        assertEquals(expectedProtagonists, finalTags("PROTAGONIST").map { it.name })
        assertEquals(15, finalTags("PROTAGONIST").size)
    }

    @Test
    fun `최종 주변인물 특징 목록은 10개이며 티켓 순서와 일치한다`() {
        assertEquals(expectedSupporting, finalTags("SUPPORTING_CHARACTER").map { it.name })
        assertEquals(10, finalTags("SUPPORTING_CHARACTER").size)
    }

    @Test
    fun `카테고리별 sort_order는 1-based 10 단위로 순차 부여된다`() {
        listOf("GENRE", "PROTAGONIST", "SUPPORTING_CHARACTER").forEach { category ->
            val orders = finalTags(category).map { it.sortOrder }
            val expected = (1..orders.size).map { it * 10 }
            assertEquals(expected, orders, "$category 카테고리 sort_order가 10 단위 순차가 아닙니다: $orders")
        }
    }

    @Test
    fun `최종 목록은 카테고리 안에서 이름이 중복되지 않는다`() {
        listOf("GENRE", "PROTAGONIST", "SUPPORTING_CHARACTER").forEach { category ->
            val names = finalTags(category).map { it.name }
            assertEquals(names.size, names.toSet().size, "$category 카테고리에 중복 이름이 있습니다: $names")
        }
    }

    private val expectedDeactivated = setOf(
        "GENRE" to "계약 결혼", "GENRE" to "던전", "GENRE" to "악역물", "GENRE" to "육아물",
        "GENRE" to "복수극", "GENRE" to "성장물",
        "PROTAGONIST" to "냉정한", "PROTAGONIST" to "복수형", "PROTAGONIST" to "헌신적인", "PROTAGONIST" to "숨겨진 강자",
        "PROTAGONIST" to "능글맞은", "PROTAGONIST" to "망나니", "PROTAGONIST" to "보호자형", "PROTAGONIST" to "선한 인물",
        "PROTAGONIST" to "집요한", "PROTAGONIST" to "두뇌파", "PROTAGONIST" to "겉은 약해도 강한", "PROTAGONIST" to "악한 인물",
        "SUPPORTING_CHARACTER" to "흑막", "SUPPORTING_CHARACTER" to "충성스러운", "SUPPORTING_CHARACTER" to "수상한",
        "SUPPORTING_CHARACTER" to "동료", "SUPPORTING_CHARACTER" to "초월자", "SUPPORTING_CHARACTER" to "까칠한",
        "SUPPORTING_CHARACTER" to "스승", "SUPPORTING_CHARACTER" to "비밀스러운", "SUPPORTING_CHARACTER" to "호위무사",
        "SUPPORTING_CHARACTER" to "귀족", "SUPPORTING_CHARACTER" to "조력자", "SUPPORTING_CHARACTER" to "장난기 많은",
        "SUPPORTING_CHARACTER" to "후회하는", "SUPPORTING_CHARACTER" to "능글맞은",
    )

    @Test
    fun `비활성화 대상은 티켓의 32건 구성과 정확히 일치한다`() {
        val block = requireNotNull(
            Regex("""SET is_active = FALSE[\s\S]*?IN \(([\s\S]*?)\);""").find(migrationSql),
        ) { "비활성화 IN 목록을 찾을 수 없습니다." }.groupValues[1]
        val pairs = Regex("""\(\s*'(GENRE|PROTAGONIST|SUPPORTING_CHARACTER)'\s*,\s*'([^']+)'\s*\)""")
            .findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
        // 개수만이 아니라 (category, name) 구성 전체를 대조한다(한 항목을 잘못 바꿔도 개수만 같으면 통과하는 구멍 방지).
        assertEquals(32, pairs.size)
        assertEquals(pairs.size, pairs.toSet().size, "비활성화 목록에 중복 항목이 있습니다: $pairs")
        assertEquals(expectedDeactivated, pairs.toSet())
    }
}
