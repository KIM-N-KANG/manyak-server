package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionSizeIntegrationTests {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var rows: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var transactions: SubmissionTransactions
    @Autowired lateinit var tokens: JwtTokenProvider
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var cleaner: DatabaseCleaner
    @MockitoBean lateinit var storage: UploadedImageStorage
    @MockitoBean lateinit var ai: StoryModerationClient
    @BeforeEach fun clean() {
        cleaner.cleanAll()
        `when`(storage.isEnabled()).thenReturn(true)
        `when`(storage.head(anyString())).thenReturn(UploadedObject("image/webp", 5L * 1024 * 1024))
        doAnswer { "https://cdn.test/${it.getArgument<String>(0)}" }.`when`(storage).serveUrlOf(anyString())
    }
    private fun request(user: User, images: Int = 7): String {
        val entries = (1..images).joinToString(",") { """{"objectKey":"characters/uploaded/drafts/${user.publicId}/$it.webp","imageName":"세린_$it"}""" }
        return """{"title":"제목","oneLineIntro":"소개","genres":["판타지"],
          "storySettings":{"worldSetting":"세계","characterSetting":"인물","userRoleSetting":"역할","ruleSetting":"규칙"},
          "startSettings":[{"name":"시작","prologue":"도입","startSituation":"상황","suggestedInputs":["하나","둘","셋"]}],
          "characters":[{"name":"세린","images":[$entries]}]}"""
    }
    private fun auth(user: User) = "Bearer ${tokens.issueAccessToken(user.publicId)}"
    @Test fun `POST 합계 초과는 저장이나 AI 호출 없이 코드가 있는 400이다`() {
        val user = users.save(User(nickname = "작가"))
        client.post().uri("/api/v1/stories/general").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(request(user)).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("IMAGES_TOO_LARGE")
        assertEquals(0, rows.count())
        verifyNoInteractions(ai)
    }
    @Test fun `허용 용량의 업로드는 검증 HEAD를 한 번만 사용한다`() {
        val user = users.save(User(nickname = "작가"))
        client.post().uri("/api/v1/stories/general").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(request(user, 1)).exchange().expectStatus().isAccepted
        verify(storage, times(1)).head("characters/uploaded/drafts/${user.publicId}/1.webp")
    }
    @Test fun `PUT 초과는 이전 실패본을 보존하고 PENDING이면 409가 우선한다`() {
        val user = users.save(User(nickname = "작가"))
        val accepted = service.create(mapper.readValue(request(user, 0), CreateGeneralStoryRequest::class.java), user.id)
        client.put().uri("/api/v1/stories/submissions/${accepted.submissionId}").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(request(user)).exchange().expectStatus().isEqualTo(409)
        val row = rows.findAll().single()
        transactions.fail(row.id, row.attempt, "APPLY_FAILED")
        client.put().uri("/api/v1/stories/submissions/${accepted.submissionId}").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(request(user)).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("IMAGES_TOO_LARGE")
        assertEquals(row.payload, rows.findById(row.id).orElseThrow().payload)
        assertEquals(SubmissionStatus.FAILED, rows.findById(row.id).orElseThrow().status)
    }
    @Test fun `PATCH는 유지 표지의 객체 HEAD와 새 이미지 합계를 검사한다`() {
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본", thumbnailImageUrl = "https://cdn.test/thumbnails/live.webp"))
        val patch = mapper.readTree(request(user, 5)) as tools.jackson.databind.node.ObjectNode
        // 6장 * 5MiB * 4/3 = 40MiB에 JSON 바이트가 더해져 초과한다.
        client.patch().uri("/api/v1/stories/${story.publicId}").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(patch)).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("IMAGES_TOO_LARGE")
        verify(storage).head("thumbnails/live.webp")
        assertEquals(0, rows.count())
        assertEquals("원본", stories.findById(story.id).orElseThrow().title)
    }
    @Test fun `유지 이미지 HEAD 실패도 장당 5MiB로 계산한다`() {
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본", thumbnailImageUrl = "https://cdn.test/thumbnails/live.webp"))
        `when`(storage.head("thumbnails/live.webp")).thenThrow(IllegalStateException("head failed"))
        client.patch().uri("/api/v1/stories/${story.publicId}").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(request(user, 5)).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("IMAGES_TOO_LARGE")
    }
    @Test fun `CREATE 보류는 HTTP에서 PENDING만 보이고 PUT은 409 DELETE는 204이다`() {
        val user = users.save(User(nickname = "작가"))
        service.create(mapper.readValue(request(user, 0), CreateGeneralStoryRequest::class.java), user.id)
        val row = rows.findAll().single()
        row.retryCount = 2
        rows.save(row)
        transactions.fail(row.id, row.attempt, "MODERATION_UNAVAILABLE")
        val body = mapper.readTree(client.get().uri("/api/v1/stories/submissions/${row.publicId}").header("Authorization", auth(user))
            .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!)
        assertEquals("PENDING", body["status"].asText())
        assertTrue(body["errorCode"].isNull)
        listOf("heldAt", "holdReason", "retryCount", "nextAttemptAt").forEach { assertFalse(body.has(it)) }
        client.put().uri("/api/v1/stories/submissions/${row.publicId}").header("Authorization", auth(user))
            .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(mapper.readValue(request(user, 0), CreateGeneralStoryRequest::class.java).copy(genres = emptyList()))).exchange().expectStatus().isEqualTo(409)
        client.delete().uri("/api/v1/stories/submissions/${row.publicId}").header("Authorization", auth(user))
            .exchange().expectStatus().isNoContent
        assertFalse(rows.existsById(row.id))
    }

}
