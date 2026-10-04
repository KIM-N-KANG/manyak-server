package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenreCatalogIntegrationTests {
    @Autowired lateinit var authProperties: com.knk.manyak.auth.config.AuthProperties
    @Autowired lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var tags: StoryCreationTagRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner

    @BeforeEach fun before() = cleaner.cleanAll()
    @AfterEach fun after() = cleaner.cleanAll()

    private fun tag(name: String, order: Int = 0, featured: Int? = null,
                    category: SimpleStoryTagCategory = SimpleStoryTagCategory.GENRE,
                    source: StoryCreationTagSource = StoryCreationTagSource.PREDEFINED,
                    active: Boolean = true): Long {
        val id = tags.save(StoryCreationTag(name = name, category = category, tagSource = source,
            sortOrder = order, isActive = active)).id
        if (featured != null) jdbc.update("UPDATE story_creation_tags SET featured_order=? WHERE id=?", featured, id)
        return id
    }
    private fun alias(id: Long, name: String) {
        jdbc.update("INSERT INTO story_creation_tag_aliases(tag_id, alias, normalized_alias) VALUES (?, ?, ?)",
            id, name, StoryCreationTag.normalize(name))
    }
    private fun get(query: String? = null) = client.get().uri {
        it.path("/api/v1/stories/genres").apply { if (query != null) queryParam("query", query) }.build()
    }.exchange()

    @Test fun `게스트 조회와 빈 질의는 전체를 제공하고 없는 결과도 대표는 유지한다`() {
        val romance = tag("로맨스", 20, 2)
        val fantasy = tag("로맨스판타지", 10, 1)
        tag("비활성", active = false); tag("커스텀", source = StoryCreationTagSource.CUSTOM)
        tag("회귀", category = SimpleStoryTagCategory.PROTAGONIST)
        for (q in listOf(null, "", " \t ")) {
            get(q).expectStatus().isOk.expectBody()
                .jsonPath("$.genres.length()").isEqualTo(2)
                .jsonPath("$.genres[0].id").isEqualTo(fantasy)
                .jsonPath("$.genres[1].id").isEqualTo(romance)
                .jsonPath("$.featuredGenres[0].id").isEqualTo(fantasy)
                .jsonPath("$.featuredGenres[1].id").isEqualTo(romance)
        }
        get("없는검색어").expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(0).jsonPath("$.featuredGenres.length()").isEqualTo(2)
    }

    @Test fun `정식 완전 별칭 완전 정식 접두 별칭 접두 정식 포함 별칭 포함 순으로 중복 없이 반환한다`() {
        val ids = listOf("로맨스", "판타지", "로맨스판타지", "무협", "현대로맨스", "SF")
            .mapIndexed { i, name -> tag(name, 100 - i * 10) }
        alias(ids[1], "로맨스"); alias(ids[3], "로맨스무협"); alias(ids[5], "미래로맨스")
        alias(ids[0], "로맨스중복"); alias(ids[1], "판타지로맨스")
        val body = get("로맨스").expectStatus().isOk.expectBody().jsonPath("$.genres.length()").isEqualTo(6)
        ids.forEachIndexed { i, id -> body.jsonPath("$.genres[$i].id").isEqualTo(id) }
    }

    @Test fun `초성 접두가 포함보다 먼저고 같은 순위는 카탈로그 순서와 ID를 따른다`() {
        tag("현대로맨스", 1)
        val first = tag("로맨스판타지", 20)
        val second = tag("로맨스", 20)
        get(" ㄹ ㅁ ㅅ ").expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(3)
            .jsonPath("$.genres[0].id").isEqualTo(first)
            .jsonPath("$.genres[1].id").isEqualTo(second)
            .jsonPath("$.genres[2].name").isEqualTo("현대로맨스")
        get("ㄹ맨스").expectStatus().isOk.expectBody().jsonPath("$.genres.length()").isEqualTo(0)
    }

    @Test fun `공백 대소문자 별칭 검색은 정식 이름을 반환하고 비활성 별칭은 숨긴다`() {
        val id = tag("현대판타지"); alias(id, "현판")
        tag("BL"); alias(tag("비활성", active = false), "현판비활성")
        alias(tag("커스텀", source = StoryCreationTagSource.CUSTOM), "현판커스텀")
        for (q in listOf("현대 판타지", "현판")) get(q).expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(1).jsonPath("$.genres[0].name").isEqualTo("현대판타지")
        get(" B l ").expectStatus().isOk.expectBody().jsonPath("$.genres[0].name").isEqualTo("BL")
    }

    @Test fun `질의 원문 30자는 허용하고 31자는 공백이어도 400이다`() {
        get("가".repeat(30)).expectStatus().isOk
        for (q in listOf("가".repeat(31), " ".repeat(31))) get(q).expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
    }

    @Test fun `만료 서명 토큰과 위조 Authorization도 공개 카탈로그를 반환한다`() {
        val id = tag("로맨스", featured = 1)
        val provider = com.knk.manyak.auth.jwt.JwtTokenProvider(authProperties,
            java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC))
        val expired = provider.issueAccessToken(java.util.UUID.randomUUID())
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException::class.java) {
            provider.jwtDecoder().decode(expired)
        }
        for (token in listOf(expired, "not-a-real-jwt.forged.token")) {
            client.get().uri("/api/v1/stories/genres").header("Authorization", "Bearer $token")
                .exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.genres[0].id").isEqualTo(id)
                .jsonPath("$.featuredGenres[0].id").isEqualTo(id)
        }
    }

    @Test fun `쌍자음 초성과 혼합 이름은 비한글 문자를 건너뛰지 않는다`() {
        val doubled = tag("까따빠싸짜", 30)
        val prefix = tag("꿈나라BL", 20)
        val contains = tag("BL꿈나라", 10)
        tag("꿈BL나라", 1)
        get("ㄲㄸㅃㅆㅉ").expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(1).jsonPath("$.genres[0].id").isEqualTo(doubled)
        get("ㄲㄴ").expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(2)
            .jsonPath("$.genres[0].id").isEqualTo(prefix).jsonPath("$.genres[1].id").isEqualTo(contains)
        // 영문이 섞인 질의 자체는 초성 검색이 아닌 일반 이름 검색이다.
        get("blㄲㄴ").expectStatus().isOk.expectBody().jsonPath("$.genres.length()").isEqualTo(0)
        get("bl꿈").expectStatus().isOk.expectBody()
            .jsonPath("$.genres.length()").isEqualTo(1).jsonPath("$.genres[0].id").isEqualTo(contains)
    }

    @Test fun `전체 197개와 대표 15개는 자료 순서이고 simple tags는 대표와 인물만 제공한다`() {
        val names = javaClass.getResourceAsStream("/genres/catalog.csv")!!.bufferedReader().readLines()
            .drop(1).map { it.substringAfter(',') }
        val featured = javaClass.getResourceAsStream("/genres/featured.txt")!!.bufferedReader().readLines()
        val ids = names.mapIndexed { i, name -> tag(name, (i + 1) * 10,
            featured.indexOf(name).takeIf { it >= 0 }?.plus(1)) }
        tag("인물뒤", 20, category = SimpleStoryTagCategory.PROTAGONIST)
        tag("인물앞", 10, category = SimpleStoryTagCategory.PROTAGONIST)
        tag("주변", 10, category = SimpleStoryTagCategory.SUPPORTING_CHARACTER)
        val body = get().expectStatus().isOk.expectBody().jsonPath("$.genres.length()").isEqualTo(197)
            .jsonPath("$.featuredGenres.length()").isEqualTo(15)
        names.forEachIndexed { i, name -> body.jsonPath("$.genres[$i].name").isEqualTo(name) }
        featured.forEachIndexed { i, name -> body.jsonPath("$.featuredGenres[$i].id").isEqualTo(ids[names.indexOf(name)]) }
        alias(ids[names.indexOf("로맨스판타지")], "로판")
        val expectedFeatured = mapper.readTree(body.returnResult().responseBody!!)["featuredGenres"]
        for (query in listOf("", " ", "로맨스", "ㄹㅁㅅ", "로판", " b l ", "없는검색어")) {
            val actual = get(query).expectStatus().isOk.expectBody().returnResult().responseBody!!
            org.junit.jupiter.api.Assertions.assertEquals(expectedFeatured, mapper.readTree(actual)["featuredGenres"], query)
        }
        val legacy = client.get().uri("/api/v1/stories/simple/tags").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(18)
        featured.forEachIndexed { i, name -> legacy.jsonPath("$[$i].name").isEqualTo(name) }
        legacy.jsonPath("$[15].name").isEqualTo("인물앞").jsonPath("$[16].name").isEqualTo("인물뒤")
            .jsonPath("$[17].name").isEqualTo("주변")
    }
}
