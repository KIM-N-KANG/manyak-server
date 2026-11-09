package com.knk.manyak.story.controller

import com.knk.manyak.admin.entity.AdminAuditLog
import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.*
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import com.knk.manyak.support.seedProvidedGenres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class HomeSectionsIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var likes: StoryLikeRepository
    @Autowired private lateinit var tags: StoryCreationTagRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var audits: AdminAuditLogRepository
    private lateinit var actor: User
    private lateinit var official: User
    private lateinit var token: String
    private val path = "/api/v1/admin/home-sections"

    @Test fun `홈 섹션 OpenAPI는 요청 응답 필드와 필수 여부와 열거값을 노출한다`() {
        val doc = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        val endpoints = doc["paths"]
        fun schemaRef(node: JsonNode) = node["content"].first()["schema"]["$" + "ref"].asText()
        assertThat(schemaRef(endpoints[path]["post"]["requestBody"])).endsWith("/HomeSectionCreateRequest")
        assertThat(schemaRef(endpoints["$path/{sectionId}"]["patch"]["requestBody"])).endsWith("/HomeSectionUpdateRequest")
        assertThat(schemaRef(endpoints["$path/{sectionId}"]["delete"]["requestBody"])).endsWith("/HomeSectionDeleteRequest")
        assertThat(schemaRef(endpoints[path]["get"]["responses"]["200"])).endsWith("/HomeSectionListResponse")
        assertThat(schemaRef(endpoints[path]["post"]["responses"]["201"])).endsWith("/HomeSectionResponse")
        assertThat(schemaRef(endpoints["$path/{sectionId}"]["patch"]["responses"]["200"])).endsWith("/HomeSectionResponse")
        assertThat(schemaRef(endpoints["/api/v1/stories/home"]["get"]["responses"]["200"])).endsWith("/StoryHomeResponse")
        val schemas = doc["components"]["schemas"]
        val create = schemas["HomeSectionCreateRequest"]
        assertThat(create["properties"].propertyNames().toList()).contains("name", "source", "storyIds", "genres", "originalOnly", "sort", "itemLimit", "isActive", "sortOrder", "reason").doesNotContain("raw")
        assertThat(create["required"].toList().map { it.asText() }).containsExactlyInAnyOrder("name", "source", "reason")
        assertThat(schemas["HomeSectionUpdateRequest"]["required"].toList().map { it.asText() }).containsExactly("reason")
        assertThat(schemas["HomeSectionDeleteRequest"]["required"].toList().map { it.asText() }).containsExactly("reason")
        assertThat(create["properties"]["itemLimit"]["type"].toString()).contains("integer")
        assertThat(create["properties"]["isActive"]["type"].toString()).contains("boolean")
        assertThat(create["properties"]["genres"]["items"]["type"].toString()).contains("string")
        assertThat(schemas["HomeSectionStoryResponse"]["properties"].propertyNames()).contains("isListed").doesNotContain("listed")
        assertThat(schemas["HomeSectionResponse"]["properties"].propertyNames()).contains("isActive").doesNotContain("active")
        assertThat(create["properties"]["source"].toString()).contains("MANUAL", "FILTER")
        assertThat(create["properties"]["sort"].toString()).contains("popular", "latest", "likes", "chats")
        assertThat(schemas["HomeSectionResponse"]["properties"].propertyNames().toList()).contains("id", "stories", "genres", "sort", "createdAt", "updatedAt")
        assertThat(schemas["HomeSectionListResponse"]["properties"]["items"]["items"]["$" + "ref"].asText()).endsWith("/HomeSectionResponse")
        assertThat(schemas["StoryHomeResponse"]["properties"]["sections"]["items"]["$" + "ref"].asText()).endsWith("/StoryHomeSectionResponse")
        assertThat(schemas["StoryHomeSectionResponse"]["properties"]["items"]["items"]["$" + "ref"].asText()).endsWith("/StorySummaryResponse")
    }

    @BeforeEach fun setup() {
        reset(audits); cleaner.cleanAll()
        actor = users.save(User(nickname = "관리자"))
        official = users.save(User(nickname = "공식", publicId = UUID.fromString(IntegrationTest.OFFICIAL_USER_PUBLIC_ID)))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", actor.id)
        token = jwt.issueAdminToken(actor.publicId)
        seedProvidedGenres(tags, "판타지", "로맨스", "미스터리")
        tags.save(StoryCreationTag(name = "비활성", category = com.knk.manyak.story.dto.SimpleStoryTagCategory.GENRE,
            tagSource = StoryCreationTagSource.PREDEFINED, isActive = false))
    }
    private fun request(method: HttpMethod, uri: String, body: Any) = client.method(method).uri(uri)
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun create(body: Map<String, Any?>): JsonNode = mapper.readTree(request(HttpMethod.POST, path, body)
        .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody!!)
    private fun filter(extra: Map<String, Any?> = emptyMap()) = create(mapOf("name" to "필터", "source" to "FILTER", "sort" to "popular", "reason" to "추가") + extra)
    private fun manual(ids: List<Story>, extra: Map<String, Any?> = emptyMap()) = create(mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to ids.toList().map { it.publicId.toString() }, "reason" to "추가") + extra)
    private fun home(header: String? = null): JsonNode {
        val req = client.get().uri("/api/v1/stories/home")
        if (header != null) req.header("Authorization", header)
        return mapper.readTree(req.exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    }
    private fun list(): JsonNode = mapper.readTree(client.get().uri(path).header("Authorization", "Bearer $token")
        .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun ids(section: JsonNode) = section["items"].toList().map { it["id"].asText() }

    @Test fun `활성 섹션이 없으면 기본 두 섹션은 게스트도 포함하고 인증 헤더를 무시한다`() {
        val guest = stories.save(Story(title = "게스트"))
        val original = stories.save(Story(title = "공식", userId = official.id))
        stories.save(Story(title = "비공개", visibility = StoryVisibility.PRIVATE))
        stories.save(Story(title = "초안", status = StoryStatus.DRAFT))
        stories.save(Story(title = "삭제", deletedAt = Instant.now()))
        val b = home()
        assertThat(b["sections"].toList().map { it["name"].asText() }).containsExactly("인기 스토리", "오리지널 스토리")
        assertThat(b["sections"].all { it["id"].isNull }).isTrue()
        assertThat(ids(b["sections"][0])).containsExactlyInAnyOrder(guest.publicId.toString(), original.publicId.toString())
        assertThat(ids(b["sections"][1])).containsExactly(original.publicId.toString())
        assertThat(b["sections"][0]["items"].first { it["id"].asText() == guest.publicId.toString() }["author"].isNull).isTrue()
        assertThat(home("Bearer invalid")).isEqualTo(b)
        assertThat(home("Bearer ${jwt.issueAccessToken(actor.publicId)}")).isEqualTo(b)
        actor.status = com.knk.manyak.auth.entity.UserStatus.DELETED; users.saveAndFlush(actor)
        assertThat(home("Bearer ${jwt.issueAccessToken(actor.publicId)}")).isEqualTo(b)
    }

    @Test fun `수동 섹션은 순서와 기본값 감사 기록을 보존하고 비공개와 삭제를 건너뛴다`() {
        val a = stories.save(Story(title = "첫째")); val b = stories.save(Story(title = "둘째")); val c = stories.save(Story(title = "셋째"))
        val section = manual(listOf(c, a, b), mapOf("name" to "  추천  ", "itemLimit" to 2))
        assertThat(section["name"].asText()).isEqualTo("추천")
        assertThat(section["sortOrder"].asInt()).isEqualTo(1)
        assertThat(section["stories"].toList().map { it["storyId"].asText() }).containsExactly(c.publicId.toString(), a.publicId.toString(), b.publicId.toString())
        assertThat(section["sort"].isNull).isTrue()
        c.visibility = StoryVisibility.PRIVATE; stories.saveAndFlush(c)
        assertThat(ids(home()["sections"][0])).containsExactly(a.publicId.toString(), b.publicId.toString())
        a.deletedAt = Instant.now(); stories.saveAndFlush(a)
        assertThat(ids(home()["sections"][0])).containsExactly(b.publicId.toString())
        assertThat(list()["items"][0]["stories"].toList().map { it["storyId"].asText() }).containsExactly(c.publicId.toString(), b.publicId.toString())
        assertThat(list()["items"][0]["stories"][0]["isListed"].asBoolean()).isFalse()
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs", String::class.java)).isEqualTo("HOME_SECTION_CREATED")
        assertThat(jdbc.queryForObject("SELECT target_type FROM admin_audit_logs", String::class.java)).isEqualTo("HOME_SECTION")
        val state = mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs", String::class.java)!!)
        assertThat(state.propertyNames()).containsExactlyInAnyOrder("name", "source", "storyIds", "genres", "originalOnly", "sort", "itemLimit", "isActive", "sortOrder")
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_audit_logs", String::class.java)).isEqualTo(section["id"].asText())
    }

    @Test fun `필터는 장르 any-of와 공식 계정 조건을 모두 적용하고 채팅순 상한을 지킨다`() {
        val a = stories.save(Story(title = "판타지", genre = "판타지, 미스터리", userId = official.id))
        val b = stories.save(Story(title = "로맨스", genre = "로맨스", userId = official.id))
        stories.save(Story(title = "장르 밖", genre = "미스터리", userId = official.id))
        stories.save(Story(title = "타인", genre = "판타지", userId = actor.id))
        chats.save(StoryChat(storyId = a.id, currentTurn = 2))
        chats.save(StoryChat(storyId = b.id, currentTurn = 3, regeneratedCount = 2, deletedAt = Instant.now()))
        filter(mapOf("genres" to listOf("판타지", "로맨스"), "originalOnly" to true, "sort" to "chats", "itemLimit" to 1))
        assertThat(ids(home()["sections"][0])).containsExactly(b.publicId.toString())
        assertThat(home()["sections"][0]["items"][0]["turnCount"].asInt()).isEqualTo(5)
        assertThat(home()["sections"][0]["items"][0]["isOriginal"].asBoolean()).isTrue()
    }

    @Test fun `섹션 정렬과 비활성 빈 목록 및 중복을 보존하고 최대 순서 다음에 등록한다`() {
        val story = stories.save(Story(title = "공통"))
        val first = manual(listOf(story), mapOf("sortOrder" to 3))
        val second = manual(listOf(story), mapOf("sortOrder" to 3))
        filter(mapOf("genres" to listOf("로맨스"), "sortOrder" to 2))
        filter(mapOf("isActive" to false, "sortOrder" to 1))
        val last = filter()
        assertThat(last["sortOrder"].asInt()).isEqualTo(4)
        val sections = home()["sections"]
        assertThat(sections.toList().map { it["id"].asText() }).containsExactly(list()["items"][1]["id"].asText(), first["id"].asText(), second["id"].asText(), last["id"].asText())
        assertThat(sections[0]["items"].size()).isZero()
        assertThat(ids(sections[1])).isEqualTo(ids(sections[2]))
    }

    @Test fun `네 정렬과 동률 순서는 공개 목록 카드와 같다`() {
        val a = stories.save(Story(title = "이름 {username}", protagonistName = "하늘", userId = actor.id))
        val b = stories.save(Story(title = "다른", userId = official.id))
        chats.save(StoryChat(storyId = a.id, currentTurn = 20)); likes.save(StoryLike(storyId = b.id, userId = actor.id))
        for (sort in listOf("popular", "latest", "likes", "chats")) {
            val s = filter(mapOf("sort" to sort))
            val expected = mapper.readTree(client.get().uri("/api/v1/stories?sort=$sort").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)["items"]
            assertThat(home()["sections"].last()["items"]).isEqualTo(expected)
            request(HttpMethod.DELETE, "$path/${s["id"].asText()}", mapOf("reason" to "다음 정렬")).expectStatus().isNoContent
        }
    }

    @Test fun `숨긴 턴과 재생성 및 삭제 채팅을 포함한 홈 인기순 채팅순은 공개 목록과 같다`() {
        val hidden = stories.save(Story(title = "숨긴 턴 포함", userId = official.id))
        val current = stories.save(Story(title = "현재 턴만", userId = official.id))
        chats.save(StoryChat(storyId = hidden.id, currentTurn = 3, hiddenTurnCount = 3, regeneratedCount = 1, deletedAt = Instant.now()))
        chats.save(StoryChat(storyId = current.id, currentTurn = 5))
        likes.save(StoryLike(storyId = hidden.id, userId = actor.id))
        likes.save(StoryLike(storyId = current.id, userId = actor.id))
        val expectedIds = listOf(hidden.publicId.toString(), current.publicId.toString())
        val defaults = home()["sections"]
        assertThat(ids(defaults[0])).containsExactlyElementsOf(expectedIds)
        assertThat(ids(defaults[1])).containsExactlyElementsOf(expectedIds)
        for (sort in listOf("popular", "chats")) {
            val section = filter(mapOf("sort" to sort, "itemLimit" to 1))
            val public = mapper.readTree(client.get().uri("/api/v1/stories?sort=$sort&limit=1").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            assertThat(ids(public)).containsExactly(hidden.publicId.toString())
            val selected = home()["sections"][0]
            assertThat(selected["items"]).isEqualTo(public["items"])
            assertThat(selected["items"][0]["turnCount"].asInt()).isEqualTo(7)
            request(HttpMethod.DELETE, "$path/${section["id"].asText()}", mapOf("reason" to "다음 정렬")).expectStatus().isNoContent
        }
    }

    @Test fun `포크 복사 턴은 홈 기본 섹션과 필터 및 공개 목록 정렬에 더하지 않는다`() {
        val forked = stories.save(Story(title = "복사 턴 제외", userId = official.id))
        val generated = stories.save(Story(title = "새 응답 우선", userId = official.id))
        val source = chats.save(StoryChat(storyId = forked.id, currentTurn = 2, hiddenTurnCount = 2, regeneratedCount = 1))
        chats.save(StoryChat(storyId = forked.id, currentTurn = 100, forkedFromChatId = source.id, forkedFromTurn = 100,
            deletedAt = Instant.now()))
        chats.save(StoryChat(storyId = generated.id, currentTurn = 6))
        val expected = listOf(generated.publicId.toString(), forked.publicId.toString())
        for (section in home()["sections"]) assertThat(ids(section)).containsExactlyElementsOf(expected)
        for (sort in listOf("popular", "chats")) {
            val section = filter(mapOf("sort" to sort, "itemLimit" to 1))
            val public = mapper.readTree(client.get().uri("/api/v1/stories?sort=$sort&limit=1").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            assertThat(ids(public)).containsExactly(generated.publicId.toString())
            assertThat(home()["sections"][0]["items"]).isEqualTo(public["items"])
            assertThat(public["items"][0]["turnCount"].asInt()).isEqualTo(6)
            request(HttpMethod.DELETE, "$path/${section["id"].asText()}", mapOf("reason" to "다음 정렬")).expectStatus().isNoContent
        }
    }

    @Test fun `홈 인기순과 채팅순의 같은 누적 값은 public_id 내림차순으로 정렬한다`() {
        val high = stories.save(Story(title = "높은 UUID", publicId = UUID.fromString("00000000-0000-0000-0000-000000000020")))
        val low = stories.save(Story(title = "낮은 UUID", publicId = UUID.fromString("00000000-0000-0000-0000-000000000010")))
        chats.save(StoryChat(storyId = high.id, currentTurn = 1, hiddenTurnCount = 4, regeneratedCount = 1))
        chats.save(StoryChat(storyId = low.id, currentTurn = 2, hiddenTurnCount = 3, regeneratedCount = 1))
        likes.save(StoryLike(storyId = high.id, userId = actor.id))
        likes.save(StoryLike(storyId = low.id, userId = actor.id))
        for (sort in listOf("popular", "chats")) {
            val section = filter(mapOf("sort" to sort))
            val public = mapper.readTree(client.get().uri("/api/v1/stories?sort=$sort").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            assertThat(ids(public)).containsExactly(high.publicId.toString(), low.publicId.toString())
            val selected = home()["sections"][0]
            assertThat(selected["items"]).isEqualTo(public["items"])
            assertThat(selected["items"].toList().map { it["turnCount"].asInt() }).containsExactly(6, 6)
            request(HttpMethod.DELETE, "$path/${section["id"].asText()}", mapOf("reason" to "다음 정렬")).expectStatus().isNoContent
        }
    }

    @Test fun `출처별 필수 필드와 한도 및 스토리 존재를 검증한다`() {
        val story = stories.save(Story(title = "유효"))
        val base = mapOf("name" to "섹션", "source" to "FILTER", "sort" to "latest", "reason" to "검증")
        val invalid = listOf(base - "name", base - "sort", base - "reason", base + ("itemLimit" to 21), base + ("itemLimit" to 0),
            base + ("sortOrder" to 0), base + ("storyIds" to emptyList<String>()), base + ("genres" to List(11) { "판타지" }),
            base + ("name" to " "), base + ("name" to "가".repeat(31)), base + ("reason" to "가".repeat(501)), base + ("reason" to " ".repeat(500) + "사유"),
            mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to listOf(story.publicId.toString()), "reason" to "검증", "genres" to emptyList<String>()),
            mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to listOf(story.publicId.toString()), "reason" to "검증", "originalOnly" to false),
            mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to listOf(story.publicId.toString(), story.publicId.toString()), "reason" to "검증"),
            mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to listOf("bad-id"), "reason" to "검증"),
            mapOf("name" to "수동", "source" to "MANUAL", "storyIds" to listOf(UUID.randomUUID().toString()), "reason" to "검증"))
        invalid.forEach { request(HttpMethod.POST, path, it).expectStatus().isBadRequest }
        story.deletedAt = Instant.now(); stories.saveAndFlush(story)
        request(HttpMethod.POST, path, mapOf("name" to "삭제", "source" to "MANUAL", "storyIds" to listOf(story.publicId.toString()), "reason" to "검증")).expectStatus().isBadRequest
        for (genre in listOf("없는 장르", "비활성")) request(HttpMethod.POST, path, base + ("genres" to listOf("판타지", genre)))
            .expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_GENRE").jsonPath("$.details[0].field").isEqualTo("genres[1]")
        assertThat(list()["items"].size()).isZero()
    }

    @Test fun `부분 수정과 장르 제거 출처 변경은 이전 하위 데이터를 지우고 감사 전후를 남긴다`() {
        val story = stories.save(Story(title = "선택"))
        val s = filter(mapOf("genres" to listOf("판타지"), "originalOnly" to true))
        val uri = "$path/${s["id"].asText()}"
        request(HttpMethod.PATCH, uri, mapOf("name" to "변경", "isActive" to false, "reason" to "수정")).expectStatus().isOk
            .expectBody().jsonPath("$.genres[0]").isEqualTo("판타지").jsonPath("$.isActive").isEqualTo(false)
        request(HttpMethod.PATCH, uri, mapOf("isActive" to null, "reason" to "null 거절")).expectStatus().isBadRequest
        request(HttpMethod.PATCH, uri, mapOf("itemLimit" to "3", "reason" to "타입 거절")).expectStatus().isBadRequest
        request(HttpMethod.PATCH, uri, mapOf("genres" to emptyList<String>(), "reason" to "조건 제거")).expectStatus().isOk.expectBody().jsonPath("$.genres").isEmpty
        request(HttpMethod.PATCH, uri, mapOf("source" to "MANUAL", "reason" to "누락")).expectStatus().isBadRequest
        request(HttpMethod.PATCH, uri, mapOf("source" to "MANUAL", "storyIds" to listOf(story.publicId.toString()), "reason" to "출처 변경"))
            .expectStatus().isOk.expectBody().jsonPath("$.sort").isEmpty.jsonPath("$.originalOnly").isEqualTo(false).jsonPath("$.genres").isEmpty
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_section_genres", Long::class.java)).isZero()
        val row = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id DESC LIMIT 1")
        assertThat(row["action"]).isEqualTo("HOME_SECTION_UPDATED")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)!!)["source"].asText()).isEqualTo("FILTER")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)!!)["source"].asText()).isEqualTo("MANUAL")
        request(HttpMethod.PATCH, uri, mapOf("source" to "FILTER", "sort" to "likes", "genres" to listOf("로맨스"), "reason" to "필터 복귀"))
            .expectStatus().isOk.expectBody().jsonPath("$.genres[0]").isEqualTo("로맨스")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_section_stories", Long::class.java)).isZero()
    }

    @Test fun `삭제는 사유를 요구하고 자식만 지우며 기본 섹션으로 돌아간다`() {
        val story = stories.save(Story(title = "유지")); val s = manual(listOf(story))
        val uri = "$path/${s["id"].asText()}"
        request(HttpMethod.DELETE, uri, emptyMap<String, Any>()).expectStatus().isBadRequest
        request(HttpMethod.DELETE, uri, mapOf("reason" to "삭제")).expectStatus().isNoContent
        request(HttpMethod.DELETE, uri, mapOf("reason" to "재삭제")).expectStatus().isNotFound
        request(HttpMethod.PATCH, uri, mapOf("reason" to "없음")).expectStatus().isNotFound
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM home_section_stories", Long::class.java)).isZero()
        assertThat(stories.existsById(story.id)).isTrue()
        assertThat(home()["sections"].all { it["id"].isNull }).isTrue()
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("HOME_SECTION_DELETED")
    }

    @Test fun `역할 없는 관리자 토큰은 거절하고 비활성 섹션만 있으면 기본 목록을 제공한다`() {
        filter(mapOf("isActive" to false))
        assertThat(home()["sections"].all { it["id"].isNull }).isTrue()
        jdbc.update("DELETE FROM admin_accounts WHERE user_id = ?", actor.id)
        request(HttpMethod.POST, path, mapOf("name" to "불가", "reason" to "불가", "source" to "FILTER", "sort" to "latest")).expectStatus().isForbidden
    }

    @Test fun `감사 삽입 실패는 등록 수정 삭제와 하위 데이터 변경도 롤백한다`() {
        val a = stories.save(Story(title = "첫째")); val b = stories.save(Story(title = "둘째"))
        val section = manual(listOf(a)); val id = section["id"].asText()
        doThrow(org.springframework.dao.DataIntegrityViolationException("audit failure")).`when`(audits).save(any(AdminAuditLog::class.java)
            ?: AdminAuditLog(adminUserId = actor.id, category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
                action = com.knk.manyak.admin.entity.AdminAuditAction.ORIGINAL_CREATED, targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.STORY, targetId = id, requestId = "matcher"))
        request(HttpMethod.PATCH, "$path/$id", mapOf("name" to "실패", "storyIds" to listOf(b.publicId.toString()), "reason" to "실패")).expectStatus().is5xxServerError
        request(HttpMethod.DELETE, "$path/$id", mapOf("reason" to "실패")).expectStatus().is5xxServerError
        request(HttpMethod.POST, path, mapOf("name" to "실패", "source" to "FILTER", "sort" to "latest", "reason" to "실패")).expectStatus().is5xxServerError
        assertThat(list()["items"].size()).isEqualTo(1)
        assertThat(list()["items"][0]["name"].asText()).isEqualTo("수동")
        assertThat(ids(home()["sections"][0])).containsExactly(a.publicId.toString())
    }

    @Test fun `기본 상한은 20이고 공식 계정이 없으면 기본 오리지널은 빈 목록이다`() {
        repeat(25) { stories.save(Story(title = "게스트$it")) }
        users.delete(official)
        val b = home()
        assertThat(b["sections"][0]["items"].size()).isEqualTo(20)
        assertThat(b["sections"][1]["items"].size()).isZero()
    }

    @Test fun `수동 지정 순서는 교체할 수 있고 정식 장르 10개까지 정규화한다`() {
        val a = stories.save(Story(title = "하나")); val b = stories.save(Story(title = "둘"))
        val s = manual(listOf(a, b))
        request(HttpMethod.PATCH, "$path/${s["id"].asText()}", mapOf("storyIds" to listOf(b.publicId.toString(), a.publicId.toString()), "reason" to "순서 교체"))
            .expectStatus().isOk
        assertThat(ids(home()["sections"][0])).containsExactly(b.publicId.toString(), a.publicId.toString())
        val names = (1..10).map { "테스트장르$it" }
        seedProvidedGenres(tags, *names.toTypedArray())
        val f = filter(mapOf("genres" to names.map { " $it " }))
        assertThat(f["genres"].size()).isEqualTo(10)
    }
}
