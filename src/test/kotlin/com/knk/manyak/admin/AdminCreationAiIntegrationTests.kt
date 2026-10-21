package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.aicall.*
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.*
import com.knk.manyak.story.repository.*
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.ai.story.stub=true"])
class AdminCreationAiIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tags: StoryCreationTagRepository
    @Autowired private lateinit var logs: AiCallLogRepository
    @MockitoSpyBean private lateinit var stories: StoryRepository
    @Autowired private lateinit var mapper: ObjectMapper
    private lateinit var token: String
    private lateinit var device: String
    private var tagId: Long = 0
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "제작 조회 관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
        device = "creation-ai-${UUID.randomUUID()}"
        tagId = tags.save(StoryCreationTag(category = SimpleStoryTagCategory.GENRE, name = "판타지", tagSource = StoryCreationTagSource.PREDEFINED)).id
    }
    private fun json(response: RestTestClient.ResponseSpec): JsonNode = mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun generate() = json(client.post().uri("/api/v1/stories/simple/storylines")
        .header("X-Manyak-Device-Id", device).header("X-Manyak-Request-Id", "trace_${UUID.randomUUID()}")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("requestId" to UUID.randomUUID(), "genreTagIds" to listOf(tagId), "protagonist" to emptyMap<String, String>()))
        .exchange().expectStatus().isCreated)
    private fun complete(draft: JsonNode, requestId: UUID = UUID.randomUUID()) = client.post().uri("/api/v1/stories/simple")
        .header("X-Manyak-Device-Id", device).header("X-Manyak-Request-Id", "trace_${UUID.randomUUID()}")
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("requestId" to requestId,
            "simpleCreationId" to draft["simpleCreationId"].asLong(), "storylineId" to draft["storylines"][0]["id"].asLong(), "additionalInfos" to emptyList<String>()))
        .exchange()

    @Test fun `실제 스토리라인 생성과 완성 로그가 저장한 스토리에 연결되어 관리자 AI 필터로 조회된다`() {
        val draft = generate()
        val firstLog = logs.findAll().single()
        val other = generate()
        assertThat(logs.findAll()).allMatch { it.storyId == null }
        val requestId = UUID.randomUUID()
        val result = json(complete(draft, requestId).expectStatus().isCreated)
        val story = stories.findByPublicIdAndDeletedAtIsNull(UUID.fromString(result["id"].asText()))!!
        val all = logs.findAll()
        assertThat(all.single { it.id == firstLog.id }.storyId).isEqualTo(story.id)
        assertThat(all.single { it.feature == AiCallFeature.STORY_COMPLETION }.storyId).isEqualTo(story.id)
        assertThat(all.single { it.feature == AiCallFeature.STORYLINE_GENERATION && it.id != firstLog.id }.storyId).isNull()
        for (feature in listOf("storyline_generation", "story_completion")) {
            val page = json(client.get().uri("/api/v1/admin/stories?aiFeature=$feature&aiStatus=SUCCEEDED")
                .header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
            assertThat(page["totalElements"].asLong()).isEqualTo(1)
            assertThat(page["items"][0]["storyId"].asText()).isEqualTo(story.publicId.toString())
        }
        assertThat(json(complete(draft, requestId).expectStatus().isCreated)["id"]).isEqualTo(result["id"])
        assertThat(logs.count()).isEqualTo(3)
        assertThat(other["simpleCreationId"]).isNotEqualTo(draft["simpleCreationId"])
    }
    @Test fun `완성 저장이 실패하면 스토리라인과 완성 호출의 storyId도 연결되지 않는다`() {
        val draft = generate()
        org.mockito.Mockito.doThrow(IllegalStateException("저장 실패"))
            .`when`(stories).save(org.mockito.ArgumentMatchers.any(Story::class.java) ?: Story(title = ""))
        complete(draft).expectStatus().is5xxServerError
        assertThat(stories.count()).isZero()
        assertThat(logs.findAll()).hasSize(2).allMatch { it.storyId == null }
        assertThat(jdbc.queryForObject("SELECT status FROM story_creation_sessions WHERE id=?", String::class.java, draft["simpleCreationId"].asLong()))
            .isEqualTo("STORYLINES_GENERATED")
    }
}
