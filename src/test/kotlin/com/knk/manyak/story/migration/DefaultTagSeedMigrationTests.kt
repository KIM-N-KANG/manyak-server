package com.knk.manyak.story.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기본 제공(PREDEFINED) 태그 시드 검증.
 *
 * 테스트 프로파일은 Flyway 비활성 + H2 ddl-auto(create-drop)이므로 마이그레이션 INSERT가 실행되지 않는다.
 * (운영 PostgreSQL만 Flyway로 시드를 적재한다.) 따라서 시드 자체가 동작 변경의 핵심인 이 작업은
 * V13 마이그레이션 SQL을 직접 파싱해 카테고리별 개수·중복 케이스를 결정적으로 검증한다.
 *
 * KNK-254: 장르/주인공 특징/주변인물 특징을 각 20개로 갱신한다.
 */
class DefaultTagSeedMigrationTests {

    private val migrationSql: String =
        requireNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/V13__update_default_tags.sql"),
        ) { "V13__update_default_tags.sql 마이그레이션을 찾을 수 없습니다." }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private val expectedGenres = listOf(
        "회귀", "현대 판타지", "계약 결혼", "던전", "로맨스 판타지", "생존물", "무협", "악역물", "시스템", "육아물",
        "학원물", "헌터물", "빙의", "복수극", "게임 판타지", "재벌물", "환생", "시한부", "아포칼립스", "성장물",
    )

    private val expectedProtagonists = listOf(
        "천재", "냉정한", "복수형", "헌신적인", "숨겨진 강자", "능글맞은", "성장형", "상처 있는", "망나니", "정의로운",
        "먼치킨", "보호자형", "책임감 있는", "선한 인물", "집요한", "두뇌파", "겉은 약해도 강한", "다정한", "악한 인물", "계략적인",
    )

    private val expectedSupporting = listOf(
        "집착하는", "흑막", "사랑스러운", "라이벌", "충성스러운", "소꿉친구", "수상한", "동료", "초월자", "까칠한",
        "스승", "비밀스러운", "호위무사", "다정한", "귀족", "조력자", "장난기 많은", "가족", "후회하는", "능글맞은",
    )

    /**
     * INSERT ... VALUES 본문에서만 (tag_type, name, ...) 행의 tag_type·name 쌍을 추출한다.
     * 비활성화 UPDATE 의 NOT IN 튜플은 동일한 형식이므로, INSERT VALUES 블록(VALUES ~ ON CONFLICT)으로 범위를 제한한다.
     */
    private fun seededPairs(): List<Pair<String, String>> {
        val valuesBlock = requireNotNull(
            Regex("""INSERT INTO story_creation_tags[\s\S]*?VALUES([\s\S]*?)ON CONFLICT""")
                .find(migrationSql),
        ) { "INSERT ... VALUES ... ON CONFLICT 블록을 찾을 수 없습니다." }
            .groupValues[1]
        val regex = Regex("""\(\s*'(GENRE|PROTAGONIST|SUPPORTING_CHARACTER)'\s*,\s*'([^']+)'""")
        return regex.findAll(valuesBlock).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    private fun namesFor(category: String): List<String> =
        seededPairs().filter { it.first == category }.map { it.second }

    @Test
    fun `장르 기본 태그는 정확히 20개이며 지정된 목록과 일치한다`() {
        assertEquals(expectedGenres, namesFor("GENRE"))
        assertEquals(20, namesFor("GENRE").size)
    }

    @Test
    fun `주인공 특징 기본 태그는 정확히 20개이며 지정된 목록과 일치한다`() {
        assertEquals(expectedProtagonists, namesFor("PROTAGONIST"))
        assertEquals(20, namesFor("PROTAGONIST").size)
    }

    @Test
    fun `주변인물 특징 기본 태그는 정확히 20개이며 지정된 목록과 일치한다`() {
        assertEquals(expectedSupporting, namesFor("SUPPORTING_CHARACTER"))
        assertEquals(20, namesFor("SUPPORTING_CHARACTER").size)
    }

    @Test
    fun `다정한과 능글맞은은 주인공과 주변인물 양쪽 카테고리에 별개로 존재한다`() {
        val pairs = seededPairs().toSet()
        listOf("다정한", "능글맞은").forEach { name ->
            assertTrue(
                pairs.contains("PROTAGONIST" to name),
                "$name 은(는) PROTAGONIST 카테고리에 존재해야 한다.",
            )
            assertTrue(
                pairs.contains("SUPPORTING_CHARACTER" to name),
                "$name 은(는) SUPPORTING_CHARACTER 카테고리에 존재해야 한다.",
            )
        }
    }

    @Test
    fun `카테고리 내 태그 이름은 중복되지 않는다`() {
        listOf("GENRE", "PROTAGONIST", "SUPPORTING_CHARACTER").forEach { category ->
            val names = namesFor(category)
            assertEquals(
                names.size,
                names.toSet().size,
                "$category 카테고리에 중복 이름이 있습니다: $names",
            )
        }
    }

    @Test
    fun `기본 태그 시드는 모두 PREDEFINED 출처로 적재된다`() {
        // INSERT 문에 CUSTOM 출처가 섞이지 않아야 한다.
        assertTrue(
            migrationSql.contains("'PREDEFINED'"),
            "마이그레이션은 PREDEFINED 출처로 태그를 적재해야 한다.",
        )
        assertTrue(
            !Regex("""VALUES[\s\S]*'CUSTOM'""").containsMatchIn(migrationSql),
            "기본 태그 시드에 CUSTOM 출처가 포함되면 안 된다.",
        )
    }
}
