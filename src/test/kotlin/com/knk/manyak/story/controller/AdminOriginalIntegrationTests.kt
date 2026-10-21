package com.knk.manyak.story.controller

import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryPublicSnapshotRepository
import com.knk.manyak.story.submission.StorySubmissionRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.util.UUID

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.official-user-public-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"])
class AdminOriginalIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @org.springframework.test.context.bean.override.mockito.MockitoBean private lateinit var storage: com.knk.manyak.image.service.UploadedImageStorage
    @org.springframework.test.context.bean.override.mockito.MockitoBean private lateinit var indexer: com.knk.manyak.search.service.StorySearchIndexer
    @Autowired private lateinit var tags: com.knk.manyak.story.repository.StoryCreationTagRepository
    @Autowired private lateinit var chats: com.knk.manyak.chat.repository.StoryChatRepository
    @Autowired private lateinit var snapshots: StoryPublicSnapshotRepository
    @Autowired private lateinit var submissions: StorySubmissionRepository
    @MockitoSpyBean private lateinit var audits: AdminAuditLogRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var official: User
    private lateinit var actor: User
    private lateinit var token: String
    private val path = "/api/v1/admin/originals"

    @BeforeEach fun setup() {
        reset(audits, storage, indexer); cleaner.cleanAll()
        `when`(storage.isEnabled()).thenReturn(true)
        `when`(storage.serveUrlOf(anyString())).thenAnswer { "https://assets/" + it.arguments[0] }
        `when`(storage.presignPut(anyString(), anyString(), anyLong(), any(java.time.Duration::class.java) ?: java.time.Duration.ZERO)).thenReturn("https://upload/example")
        `when`(storage.head(anyString())).thenReturn(com.knk.manyak.image.service.UploadedObject("image/png", 100))
        official = users.save(User(publicId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), nickname = "공식"))
        tags.save(StoryCreationTag(category = com.knk.manyak.story.dto.SimpleStoryTagCategory.GENRE, name = "판타지", tagSource = StoryCreationTagSource.PREDEFINED))
        actor = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", actor.id)
        token = jwt.issueAdminToken(actor.publicId)
    }
    private fun post(body: Any) = client.post().uri(path).header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun patch(id: String, body: Any) = client.patch().uri("$path/$id").header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun json(body: String?) = mapper.readTree(body!!)
    private fun draft(): JsonNode = json(post(mapOf("title" to "초안", "reason" to "등록"))
        .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody)
    private fun complete() = mapOf("title" to "공개 원문", "oneLineIntro" to "소개", "genres" to listOf("판타지"),
        "storySettings" to mapOf("worldSetting" to "세계", "characterSetting" to "인물", "userRoleSetting" to "주인공", "ruleSetting" to "규칙"),
        "startSettings" to listOf(mapOf("name" to "시작", "prologue" to "프롤로그", "startSituation" to "상황", "suggestedInputs" to listOf("하나", "둘", "셋"))),
        "status" to "PUBLISHED", "visibility" to "PUBLIC", "reason" to "발행")

    @Test fun `제목만 초안은 공식 계정에 즉시 저장되고 공개 읽기와 제출본에는 노출되지 않는다`() {
        val body = draft()
        assertThat(body["status"].asText()).isEqualTo("DRAFT")
        assertThat(body["visibility"].asText()).isEqualTo("PRIVATE")
        assertThat(body["title"].asText()).isEqualTo("초안")
        assertThat(body["owner"]["memberId"].asText()).isEqualTo(official.publicId.toString())
        assertThat(body.has("submission")).isFalse()
        assertThat(body["startSettings"].size()).isZero()
        assertThat(submissions.count()).isZero()
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs", String::class.java)).isEqualTo("ORIGINAL_CREATED")
        client.get().uri("/api/v1/stories/${body["storyId"].asText()}").exchange().expectStatus().isNotFound
    }
    @Test fun `발행 등록은 공개 스냅샷과 감사 화이트리스트를 만들고 공개로 읽힌다`() {
        val b = json(post(complete()).expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody)
        val story = stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(b["storyId"].asText()))!!
        assertThat(snapshots.findById(story.id)).isPresent
        verify(indexer, timeout(2000)).index(story.id)
        assertThat(json(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs", String::class.java)).propertyNames()).containsExactlyInAnyOrder("status", "visibility", "title", "genres")
        client.get().uri("/api/v1/stories/${story.publicId}").exchange().expectStatus().isOk
    }
    @Test fun `초안 발행은 전체 등록 검증을 통과해야 하고 발행 뒤 초안 복귀는 거부한다`() {
        val id = draft()["storyId"].asText()
        patch(id, mapOf("status" to "PUBLISHED", "reason" to "미완성")).expectStatus().isBadRequest
        assertThat(stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(id))!!.status).isEqualTo(StoryStatus.DRAFT)
        patch(id, complete()).expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("PUBLISHED")
        val before = json(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs WHERE action = 'ORIGINAL_UPDATED'", String::class.java))
        val after = json(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs WHERE action = 'ORIGINAL_UPDATED'", String::class.java))
        assertThat(before["status"].asText()).isEqualTo("DRAFT")
        assertThat(after["status"].asText()).isEqualTo("PUBLISHED")
        assertThat(after.propertyNames()).containsExactlyInAnyOrder("status", "visibility", "title", "genres")
        patch(id, mapOf("status" to "DRAFT", "reason" to "회귀")).expectStatus().isBadRequest
        assertThat(submissions.count()).isZero()
    }
    @Test fun `등록 상태와 사유와 draftId 오류를 거부한다`() {
        listOf(mapOf("title" to "제목", "reason" to "등록", "visibility" to "PUBLIC"),
            mapOf("title" to "제목", "reason" to "등록", "draftId" to null),
            mapOf("title" to "제목"), mapOf("title" to "제목", "reason" to " "),
            mapOf("title" to "제목", "reason" to "등록", "status" to "PUBLISHED"))
            .forEach { post(it).expectStatus().isBadRequest }
        assertThat(stories.count()).isZero()
    }
    @Test fun `회원 스토리와 삭제된 스토리는 상세 수정 표지 삭제 presign 모두 404다`() {
        val ids = listOf(stories.save(Story(userId = actor.id, title = "회원")).publicId,
            stories.save(Story(userId = official.id, title = "삭제", deletedAt = java.time.Instant.now())).publicId, UUID.randomUUID())
        for (id in ids) {
            client.get().uri("$path/$id").header("Authorization", "Bearer $token").exchange().expectStatus().isNotFound
            patch(id.toString(), mapOf("title" to "수정", "reason" to "사유")).expectStatus().isNotFound
            client.method(org.springframework.http.HttpMethod.DELETE).uri("$path/$id/thumbnail").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "삭제")).exchange().expectStatus().isNotFound
            client.post().uri("$path/$id/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange().expectStatus().isNotFound
        }
    }
    @Test fun `시드 오리지널도 수정하고 표지 삭제는 멱등이며 비공개 개작은 스냅샷을 보존한다`() {
        val story = stories.save(Story(userId = official.id, title = "시드", thumbnailImageUrl = "https://assets/seed.png"))
        patch(story.publicId.toString(), mapOf("title" to "개정", "reason" to "개정")).expectStatus().isOk
        assertThat(snapshots.findById(story.id).orElseThrow().snapshot.title).isEqualTo("개정")
        patch(story.publicId.toString(), mapOf("visibility" to "PRIVATE", "title" to "비공개", "reason" to "개작")).expectStatus().isOk
        assertThat(snapshots.findById(story.id).orElseThrow().snapshot.title).isEqualTo("개정")
        repeat(2) { client.method(org.springframework.http.HttpMethod.DELETE).uri("$path/${story.publicId}/thumbnail").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "표지 삭제")).exchange().expectStatus().isOk.expectBody().jsonPath("$.thumbnailUrl").isEmpty }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = 'ORIGINAL_THUMBNAIL_DELETED'", Int::class.java)).isEqualTo(2)
    }
    @Test fun `감사 저장 실패는 등록과 수정을 롤백한다`() {
        val story = stories.save(Story(userId = official.id, title = "유지"))
        val dummy = mock(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
        doThrow(IllegalStateException("audit unavailable")).`when`(audits).save(any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: dummy)
        post(mapOf("title" to "롤백", "reason" to "사유")).expectStatus().is5xxServerError
        patch(story.publicId.toString(), mapOf("title" to "롤백", "reason" to "사유")).expectStatus().is5xxServerError
        assertThat(stories.count()).isEqualTo(1)
        assertThat(stories.findById(story.id).orElseThrow().title).isEqualTo("유지")
        assertThat(snapshots.count()).isZero()
        verify(indexer, never()).index(anyLong())
    }
    @Test fun `인증과 목록 페이지 검색 필터를 검증한다`() {
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAccessToken(actor.publicId)}").exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(official.publicId)}").exchange().expectStatus().isForbidden
        stories.saveAll(listOf(Story(userId = official.id, title = "초안", status = StoryStatus.DRAFT, visibility = StoryVisibility.PRIVATE),
            Story(userId = official.id, title = "공개"), Story(userId = official.id, title = "비공개", visibility = StoryVisibility.PRIVATE), Story(userId = actor.id, title = "제외")))
        client.get().uri("$path?sort=chatCount").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.totalElements").isEqualTo(3)
        client.get().uri("$path?status=DRAFT&visibility=PRIVATE").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
        listOf("page=0", "size=101", "sort=title", "order=bad", "cursor=a", "limit=1", "q=", "status=bad").forEach {
            client.get().uri("$path?$it").header("Authorization", "Bearer $token").exchange().expectStatus().isBadRequest
        }
    }
    @Test fun `presign은 공식 계정과 스토리 접두어를 쓰며 형식과 크기를 검증한다`() {
        val id = draft()["storyId"].asText()
        for ((uri, prefix) in listOf("$path/images/presign" to "drafts/${official.publicId}/", "$path/$id/images/presign" to "$id/")) {
            val body = json(client.post().uri(uri).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange()
                .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody)
            assertThat(body["objectKey"].asText()).startsWith("thumbnails/uploaded/$prefix")
            assertThat(body["expiresInSeconds"].asInt()).isEqualTo(600)
        }
        for ((type, length) in listOf("image/gif" to 100, "image/png" to 5242881)) {
            client.post().uri("$path/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("kind" to "COVER", "contentType" to type, "contentLength" to length)).exchange().expectStatus().isBadRequest
        }
        doThrow(IllegalStateException("presign unavailable")).`when`(storage).presignPut(anyString(), anyString(), anyLong(), any(java.time.Duration::class.java) ?: java.time.Duration.ZERO)
        client.post().uri("$path/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange().expectStatus().isEqualTo(503)
        `when`(storage.isEnabled()).thenReturn(false)
        client.post().uri("$path/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange().expectStatus().isEqualTo(503)
    }
    @Test fun `업로드 접두어와 존재를 확인하고 불변 복사본을 승인 상태로 저장한다`() {
        val key = "thumbnails/uploaded/drafts/${official.publicId}/${UUID.randomUUID()}.png"
        val characterKey = "characters/uploaded/drafts/${official.publicId}/${UUID.randomUUID()}.png"
        val b = json(post(mapOf("title" to "이미지", "reason" to "등록", "thumbnailObjectKey" to key,
            "characters" to listOf(mapOf("name" to "세린", "images" to listOf(mapOf("objectKey" to characterKey, "imageName" to "세린_웃음"))))))
            .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody)
        assertThat(b["thumbnailModerationStatus"].asText()).isEqualTo("APPROVED")
        assertThat(b["thumbnailUrl"].asText()).contains("/uploaded/moderated/").doesNotContain(key)
        assertThat(b["characters"][0]["images"][0]["moderationStatus"].asText()).isEqualTo("APPROVED")
        verify(storage, times(2)).copy(anyString(), anyString())
        clearInvocations(storage)
        post(mapOf("title" to "차단", "reason" to "등록", "thumbnailObjectKey" to "thumbnails/uploaded/drafts/${actor.publicId}/${UUID.randomUUID()}.png"))
            .expectStatus().isBadRequest
        verify(storage, never()).head(anyString())
        `when`(storage.head(key)).thenReturn(null)
        post(mapOf("title" to "없음", "reason" to "등록", "thumbnailObjectKey" to key)).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("UPLOAD_NOT_FOUND")
        val cid = b["characters"][0]["id"].asText()
        patch(b["storyId"].asText(), mapOf("reason" to "이미지 삭제", "characters" to listOf(mapOf("id" to cid, "name" to "세린", "images" to emptyList<Any>()))))
            .expectStatus().isOk.expectBody().jsonPath("$.characters[0].images.length()").isEqualTo(0)
    }
    @Test fun `이미지 이름 중복과 잘못된 소스 및 장르는 거부한다`() {
        val key = "characters/uploaded/drafts/${official.publicId}/${UUID.randomUUID()}.png"
        post(mapOf("title" to "중복", "reason" to "등록", "characters" to listOf(mapOf("name" to "세린", "images" to
            List(2) { mapOf("objectKey" to key, "imageName" to "세린_웃음") })))).expectStatus().isEqualTo(409)
        post(mapOf("title" to "소스", "reason" to "등록", "characters" to listOf(mapOf("name" to "세린", "images" to listOf(mapOf("imageName" to "세린_웃음")))))).expectStatus().isBadRequest
        post(complete() + ("genres" to listOf("없는장르"))).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_GENRE")
    }
    @Test fun `목록은 채팅 집계와 공개 식별자 검색 및 안정된 페이지 정렬을 제공한다`() {
        val first = stories.save(Story(userId = official.id, title = "Search Title"))
        val second = stories.save(Story(userId = official.id, title = "other"))
        chats.save(com.knk.manyak.chat.entity.StoryChat(storyId = first.id, userId = actor.id, currentTurn = 7))
        chats.save(com.knk.manyak.chat.entity.StoryChat(storyId = second.id, userId = actor.id, currentTurn = 99, deletedAt = java.time.Instant.now()))
        client.get().uri("$path?sort=totalTurns&size=1").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].storyId").isEqualTo(first.publicId.toString()).jsonPath("$.items[0].totalTurns").isEqualTo(7).jsonPath("$.items[0].chatCount").isEqualTo(1).jsonPath("$.totalPages").isEqualTo(2)
        for (q in listOf("SEARCH", second.publicId.toString())) client.get().uri("$path?q=$q").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(1)
        client.get().uri("$path?page=100").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(0)
    }
    @Test fun `설정된 공식 회원이 없으면 생성과 presign은 503이고 목록은 빈 페이지다`() {
        users.delete(official)
        post(mapOf("title" to "생성", "reason" to "사유")).expectStatus().isEqualTo(503)
        client.post().uri("$path/images/presign").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("kind" to "COVER", "contentType" to "image/png", "contentLength" to 100)).exchange().expectStatus().isEqualTo(503)
        client.get().uri(path).header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.totalElements").isEqualTo(0)
    }

    @Test fun `이름 토큰 원문과 시작 설정 identity를 유지하며 인물을 동기화한다`() {
        val created = json(post(complete() + mapOf("title" to "{username}의 스토리", "protagonistName" to "주인공",
            "characters" to listOf(mapOf("name" to "세린", "description" to "인물 소개"))))
            .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody)
        assertThat(created["title"].asText()).isEqualTo("{username}의 스토리")
        val id = created["storyId"].asText()
        val start = created["startSettings"][0]["id"].asText()
        val character = created["characters"][0]["id"].asText()
        val updated = json(patch(id, mapOf("reason" to "동기화", "startSettings" to listOf(mapOf("id" to start, "name" to "새 시작",
            "prologue" to "변경 프롤로그", "startSituation" to "변경 상황", "suggestedInputs" to listOf("하나", "둘", "셋"))),
            "characters" to listOf(mapOf("id" to character, "name" to "새린"))))
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody)
        assertThat(updated["startSettings"][0]["id"].asText()).isEqualTo(start)
        assertThat(updated["characters"][0]["id"].asText()).isEqualTo(character)
        assertThat(updated["characters"][0]["description"].asText()).isEqualTo("인물 소개")
        assertThat(submissions.count()).isZero()
    }
    @Test fun `저장소 조회나 복사 장애는 503이며 스토리를 남기지 않는다`() {
        val key = "thumbnails/uploaded/drafts/${official.publicId}/${UUID.randomUUID()}.png"
        `when`(storage.head(key)).thenThrow(IllegalStateException("storage unavailable"))
        post(mapOf("title" to "조회 장애", "reason" to "검증", "thumbnailObjectKey" to key)).expectStatus().isEqualTo(503)
        doReturn(com.knk.manyak.image.service.UploadedObject("image/png", 100)).`when`(storage).head(key)
        doThrow(IllegalStateException("copy unavailable")).`when`(storage).copy(anyString(), anyString())
        post(mapOf("title" to "복사 장애", "reason" to "검증", "thumbnailObjectKey" to key)).expectStatus().isEqualTo(503)
        assertThat(stories.count()).isZero()
    }

    @Test fun `오리지널 표지 삭제는 기존 제출본 상태를 거치지 않고 직접 적용한다`() {
        val story = stories.save(Story(userId = official.id, title = "기존 원고", thumbnailImageUrl = "https://assets/old.png"))
        val submission = submissions.save(com.knk.manyak.story.submission.StorySubmission(userId = official.id, storyId = story.id,
            kind = com.knk.manyak.story.submission.SubmissionKind.UPDATE, payload = "{}"))
        client.method(org.springframework.http.HttpMethod.DELETE).uri("$path/${story.publicId}/thumbnail").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "즉시 반영")).exchange().expectStatus().isOk.expectBody().jsonPath("$.thumbnailUrl").isEmpty
        assertThat(submissions.findById(submission.id).orElseThrow().status).isEqualTo(com.knk.manyak.story.submission.SubmissionStatus.PENDING)
        assertThat(submissions.count()).isEqualTo(1)
    }

}
