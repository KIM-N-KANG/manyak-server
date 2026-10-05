package com.knk.manyak.story.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** H2는 Flyway를 실행하지 않는다. SQL 목록 계약만 검증하며 실제 적용은 gen-db-docs.sh로 검증한다. */
class GenreCatalogMigrationTests {
    private val sql get() = requireNotNull(javaClass.getResourceAsStream("/db/migration/V91__genre_catalog.sql"))
        .bufferedReader().use { it.readText() }

    @Test fun `197개 정식 이름과 순서 및 대표 15개는 제공 자료와 일치한다`() {
        val expected = javaClass.getResourceAsStream("/genres/catalog.csv")!!.bufferedReader().readLines()
            .drop(1).map { it.substringAfter(',') }
        val featured = javaClass.getResourceAsStream("/genres/featured.txt")!!.bufferedReader().readLines()
        val block = sql.substringAfter("INSERT INTO knk1537_catalog").substringBefore(";")
        val rows = Regex("""\('([^']+)', (\d+), (NULL|\d+)\)""").findAll(block).toList()
        assertEquals(expected, rows.map { it.groupValues[1] })
        assertEquals((1..197).map { it * 10 }, rows.map { it.groupValues[2].toInt() })
        assertEquals(featured, rows.filter { it.groupValues[3] != "NULL" }
            .sortedBy { it.groupValues[3].toInt() }.map { it.groupValues[1] })
        assertEquals(197, expected.toSet().size)
    }

    @Test fun `별칭은 승인된 18개이며 정규화만 필요한 표기는 중복 시드하지 않는다`() {
        val expected = mapOf("헌터" to "헌터물", "학원" to "학원물", "재벌" to "재벌물", "게임" to "게임판타지",
            "중세판타지" to "정통판타지", "육아물" to "육아", "복수극" to "복수", "악역물" to "악역",
            "로판" to "로맨스판타지", "현판" to "현대판타지", "겜판" to "게임판타지", "퓨판" to "퓨전판타지",
            "정판" to "정통판타지", "로코" to "로맨틱코미디", "아포" to "아포칼립스", "비엘" to "BL", "지엘" to "GL", "에스에프" to "SF")
        val block = sql.substringAfter("-- ALIAS SEEDS").substringBefore("-- END ALIAS SEEDS")
        val rows = Regex("""\('([^']+)', '([^']+)'\)""").findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(18, rows.size)
        assertEquals(expected, rows.toMap())
    }

    @Test fun `개명은 참조 이전 뒤 중복만 삭제하고 CUSTOM과 인물 특징은 대상이 아니다`() {
        assertTrue(sql.indexOf("UPDATE story_creation_session_tags") < sql.indexOf("DELETE FROM story_creation_tags"))
        assertTrue(sql.indexOf("UPDATE image_preset_genres") < sql.indexOf("DELETE FROM story_creation_tags"))
        assertTrue(sql.contains("IS NOT DISTINCT FROM"))
        assertTrue(sql.contains("ON CONFLICT (tag_source, tag_type, normalized_name)"))
        assertTrue(sql.contains("UPDATE lorebooks"))
        assertTrue(sql.contains("tag_source = 'PREDEFINED' AND tag_type = 'GENRE'"))
    }
}
