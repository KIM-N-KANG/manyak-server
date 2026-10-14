package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryDraftControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var requests: StoryCreationRequestRepository
    @Autowired private lateinit var drafts: com.knk.manyak.user.draft.StoryDraftRepository
    @Autowired private lateinit var service: com.knk.manyak.user.draft.StoryDraftService
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private lateinit var images: com.knk.manyak.image.service.UploadedImageStorage
    private lateinit var user: User
    private lateinit var auth: String
    private val path = "/api/v1/users/me/story-drafts"
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        user = users.save(User(nickname = "작가"))
        auth = "Bearer ${jwt.issueAccessToken(user.publicId)}"
    }
    private fun body(payload: String = "{}", extra: String = "") = """{"mode":"GENERAL","stage":"GENERAL_DRAFT","schemaVersion":1,"payload":$payload$extra}"""
    private fun put(id: UUID, body: String) = client.put().uri("$path/$id").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun get(id: UUID) = client.get().uri("$path/$id").header("Authorization", auth).exchange()
    private fun delete(id: UUID) = client.delete().uri("$path/$id").header("Authorization", auth).exchange()

    @Test fun `65536 바이트는 허용하고 한 바이트 초과는 거부한다`() {
        val payload = """{"x":"${"a".repeat(65_528)}"}"""
        assertEquals(65_536, payload.toByteArray(Charsets.UTF_8).size)
        put(UUID.randomUUID(), body(payload)).expectStatus().isOk
        put(UUID.randomUUID(), body(payload.replace("aaa", "aaaa"))).expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("DRAFT_PAYLOAD_TOO_LARGE")
    }
    @Test fun `이미지 객체가 없어도 HEAD 없이 저장하고 읽기 URL을 반환한다`() {
        val cover = "thumbnails/uploaded/drafts/${user.publicId}/missing.png"
        val character = "characters/uploaded/drafts/${user.publicId}/missing.webp"
        org.mockito.Mockito.`when`(images.serveUrlOf(cover)).thenReturn("https://assets.example/$cover")
        org.mockito.Mockito.`when`(images.serveUrlOf(character)).thenReturn("https://assets.example/$character")
        val id = UUID.randomUUID()
        put(id, body("""{"nested":[{"key":"$character"},"$cover"]}""", ",\"thumbnailObjectKey\":\"$cover\"")).expectStatus().isOk
        val bytes = get(id).expectStatus().isOk.expectBody().returnResult().responseBody!!
        val detail = mapper.readTree(bytes)
        assertEquals("https://assets.example/$cover", detail.path("thumbnailUrl").asText())
        assertEquals("https://assets.example/$character", detail.path("imageUrls").path(character).asText())
        assertEquals("https://assets.example/$cover", detail.path("imageUrls").path(cover).asText())
        org.mockito.Mockito.verify(images, org.mockito.Mockito.never()).head(cover)
        org.mockito.Mockito.verify(images, org.mockito.Mockito.never()).head(character)
    }
    @Test fun `설정되지 않은 이미지 저장소는 이미지 읽기에 503이고 저장에는 영향이 없다`() {
        val key = "thumbnails/uploaded/drafts/${user.publicId}/missing.png"
        val id = UUID.randomUUID()
        put(id, body("{}", ",\"thumbnailObjectKey\":\"$key\"")).expectStatus().isOk
        get(id).expectStatus().isEqualTo(503)
    }
    @Test fun `동시 신규 초안은 회원 잠금으로 다섯 개를 넘지 않는다`() {
        repeat(4) { put(UUID.randomUUID(), body()).expectStatus().isOk }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (1..2).map { pool.submit<Int> {
                ready.countDown(); check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    service.put(user.id, UUID.randomUUID().toString(), mapper.readValue(body(), com.knk.manyak.user.draft.PutStoryDraftRequest::class.java)); 200
                } catch (e: org.springframework.web.server.ResponseStatusException) { e.statusCode.value() }
            } }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown()
            assertEquals(listOf(200, 400), results.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.sorted())
            assertEquals(5L, drafts.countByUserId(user.id))
        } finally { pool.shutdownNow() }
    }
    @Test fun `동시 갱신은 같은 기준 버전에서 한번만 성공한다`() {
        val id = UUID.randomUUID(); put(id, body()).expectStatus().isOk
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2); val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (1..2).map { value -> pool.submit<Int> {
                ready.countDown(); check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                try {
                    service.put(user.id, id.toString(), mapper.readValue(body("{\"x\":$value}", ",\"baseVersion\":1"), com.knk.manyak.user.draft.PutStoryDraftRequest::class.java)); 200
                } catch (e: org.springframework.web.server.ResponseStatusException) { e.statusCode.value() }
            } }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown()
            assertEquals(listOf(200, 409), results.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.sorted())
            assertEquals(2, drafts.findById(id).orElseThrow().version)
        } finally { pool.shutdownNow() }
    }
    @Test fun `다른 회원이 같은 신규 UUID를 동시에 저장해도 소유권을 덮어쓰지 않는다`() {
        val other = users.save(User(nickname = "다른 작가"))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(10) {
                val id = UUID.randomUUID()
                val ready = java.util.concurrent.CountDownLatch(2); val start = java.util.concurrent.CountDownLatch(1)
                val results = listOf(user, other).map { owner -> pool.submit<Int> {
                    ready.countDown(); check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    try {
                        service.put(owner.id, id.toString(), mapper.readValue(body("{\"owner\":${owner.id}}"), com.knk.manyak.user.draft.PutStoryDraftRequest::class.java)); 200
                    } catch (e: org.springframework.web.server.ResponseStatusException) { e.statusCode.value() }
                      catch (_: org.springframework.dao.DataIntegrityViolationException) { 500 }
                } }
                check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown()
                assertEquals(listOf(200, 404), results.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }.sorted())
                assertEquals(1, drafts.findAll().size)
                val stored = drafts.findById(id).orElseThrow()
                assertEquals(stored.userId, mapper.readTree(stored.payload).path("owner").asLong())
                service.delete(stored.userId, id.toString())
            }
        } finally { pool.shutdownNow() }
    }
    @Test fun `같은 JSON 재전송은 버전과 시각을 유지하고 변경은 기준 버전을 검사한다`() {
        val id = UUID.randomUUID()
        val first = put(id, body("""{"a":1,"b":[2,3]}""")).expectStatus().isOk.expectBody().returnResult().responseBody!!
        val replay = put(id, body("""{ "b": [2,3], "a": 1 }""", ",\"baseVersion\":999")).expectStatus().isOk.expectBody().returnResult().responseBody!!
        assertEquals(mapper.readTree(first), mapper.readTree(replay))
        put(id, body("""{"b":[3,2],"a":1}""")).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("DRAFT_VERSION_CONFLICT").jsonPath("$.details[0].message").isEqualTo("1").jsonPath("$.details[1].field").isEqualTo("updatedAt")
        put(id, body("{}", ",\"baseVersion\":1,\"title\":\"제목\"")).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(2)
        get(id).expectStatus().isOk.expectBody().jsonPath("$.payload").isEqualTo(emptyMap<String, Any>()).jsonPath("$.title").isEqualTo("제목")
        put(id, body("{}", ",\"baseVersion\":2")).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(3)
        get(id).expectStatus().isOk.expectBody().jsonPath("$.title").isEqualTo(null)
    }
    @Test fun `타인 초안은 조회 저장 404 삭제 204이며 원본이 유지된다`() {
        val id = UUID.randomUUID(); put(id, body()).expectStatus().isOk
        val ownerAuth = auth
        auth = "Bearer ${jwt.issueAccessToken(users.save(User(nickname = "다른 작가")).publicId)}"
        get(id).expectStatus().isNotFound; put(id, body()).expectStatus().isNotFound
        delete(id).expectStatus().isNoContent
        auth = ownerAuth; get(id).expectStatus().isOk
        delete(id).expectStatus().isNoContent; delete(id).expectStatus().isNoContent
        get(id).expectStatus().isNotFound
    }
    @Test fun `payload 객체 크기 단계와 이미지 prefix를 검증한다`() {
        for (payload in listOf("[]", "null", "1", "\"text\"")) put(UUID.randomUUID(), body(payload)).expectStatus().isBadRequest
        put(UUID.randomUUID(), body("""{"text":"${"가".repeat(22000)}"}""")).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("DRAFT_PAYLOAD_TOO_LARGE")
        put(UUID.randomUUID(), body().replace("GENERAL_DRAFT", "KEYWORD_DRAFT")).expectStatus().isBadRequest
        put(UUID.randomUUID(), body("""{"nested":[{"image":"characters/uploaded/drafts/${UUID.randomUUID()}/x.png"}]}""")).expectStatus().isBadRequest
        put(UUID.randomUUID(), body("{}", ",\"thumbnailObjectKey\":\"thumbnails/uploaded/other/x.png\"")).expectStatus().isBadRequest
        put(UUID.randomUUID(), "{}").expectStatus().isBadRequest
        put(UUID.randomUUID(), body().replace("\"schemaVersion\":1,", "")).expectStatus().isBadRequest
    }
    @Test fun `여섯 번째 신규는 거부하지만 기존 갱신은 허용한다`() {
        val ids = (1..5).map { UUID.randomUUID() }
        ids.forEach { put(it, body()).expectStatus().isOk }
        put(UUID.randomUUID(), body()).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("DRAFT_LIMIT_EXCEEDED")
        put(ids.first(), body("{\"x\":1}", ",\"baseVersion\":1")).expectStatus().isOk
    }
    @Test fun `미인증과 정지 회원은 네 API 모두 거부한다`() {
        val id = UUID.randomUUID()
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        auth = ""; get(id).expectStatus().isUnauthorized; put(id, body()).expectStatus().isUnauthorized; delete(id).expectStatus().isUnauthorized
        auth = "Bearer ${jwt.issueAccessToken(user.publicId)}"
        user.status = UserStatus.SUSPENDED; users.saveAndFlush(user)
        client.get().uri(path).header("Authorization", auth).exchange().expectStatus().isForbidden
        get(id).expectStatus().isForbidden; put(id, body()).expectStatus().isForbidden; delete(id).expectStatus().isForbidden
    }
    @Test fun `목록은 유효한 내 PENDING 두 단계와 초안을 최초 생성 시각 역순으로 합친다`() {
        val now = Instant.now()
        requests.saveAll(listOf(
            StoryCreationRequest(requestId = UUID.randomUUID(), userId = user.id, stage = StoryCreationStage.STORYLINE_GENERATION, createdAt = now.minusSeconds(10)),
            StoryCreationRequest(requestId = UUID.randomUUID(), userId = user.id, stage = StoryCreationStage.STORY_COMPLETION, createdAt = now.plusSeconds(10)),
            StoryCreationRequest(requestId = UUID.randomUUID(), userId = user.id, stage = StoryCreationStage.STORY_COMPLETION, updatedAt = now.minusSeconds(301)),
            StoryCreationRequest(requestId = UUID.randomUUID(), userId = user.id, stage = StoryCreationStage.STORY_COMPLETION, status = StoryCreationRequestStatus.COMPLETED),
            StoryCreationRequest(requestId = UUID.randomUUID(), userId = users.save(User(nickname = "타인")).id, stage = StoryCreationStage.STORYLINE_GENERATION)
        ))
        put(UUID.randomUUID(), body()).expectStatus().isOk
        client.get().uri(path).header("Authorization", auth).exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.length()").isEqualTo(3).jsonPath("$[0].stage").isEqualTo("STORY_COMPLETION")
            .jsonPath("$[1].kind").isEqualTo("DRAFT").jsonPath("$[1].payload").doesNotExist()
            .jsonPath("$[2].stage").isEqualTo("STORYLINE_GENERATION")
    }
    @Test fun `스토리 초안은 내 스토리라인 요청만 연결하고 복구 스키마를 반환한다`() {
        val id = UUID.randomUUID(); val requestId = UUID.randomUUID()
        val request = body("{}", ",\"generationRequestId\":\"$requestId\"").replace("GENERAL", "SIMPLE").replace("SIMPLE_DRAFT", "STORY_DRAFT")
        put(id, request).expectStatus().isBadRequest
        requests.save(StoryCreationRequest(requestId = requestId, userId = user.id, stage = StoryCreationStage.STORYLINE_GENERATION, status = StoryCreationRequestStatus.COMPLETED, resultJson = "{\"simpleCreationId\":7}"))
        put(id, request).expectStatus().isOk
        get(id).expectStatus().isOk.expectBody().jsonPath("$.generation.status").isEqualTo("COMPLETED").jsonPath("$.generation.result.simpleCreationId").isEqualTo(7)
        val wrong = requests.save(StoryCreationRequest(requestId = UUID.randomUUID(), userId = user.id, stage = StoryCreationStage.STORY_COMPLETION))
        put(UUID.randomUUID(), request.replace(requestId.toString(), wrong.requestId.toString())).expectStatus().isBadRequest
    }
}
