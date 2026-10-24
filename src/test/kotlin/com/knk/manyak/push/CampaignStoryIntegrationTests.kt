package com.knk.manyak.push

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.push.outbox.PushOutboxRelay
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.push.mode=local"])
class LocalCampaignStoryIntegrationTests : CampaignStoryIntegrationTests()

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.push.mode=remote"])
class RemoteCampaignStoryIntegrationTests : CampaignStoryIntegrationTests()

/** 같은 예약 계약과 발송 데이터 검증을 로컬 FCM·원격 아웃박스 경로에 모두 적용한다. */
@AutoConfigureRestTestClient
abstract class CampaignStoryIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var promotion: PromotionPushService
    @MockitoBean private lateinit var sender: FcmPushSender
    @MockitoBean private lateinit var store: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    private val mapper = ObjectMapper()
    private val root = "/api/v1/admin/push/campaigns"
    private lateinit var admin: User
    private lateinit var token: String
    private val data = mutableListOf<Map<String, String>>()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        data.clear()
        admin = users.save(User(nickname = "관리자", marketingPushAgreedAt = Instant.EPOCH,
            marketingPushNightAgreedAt = Instant.EPOCH))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
        tokens.save(DevicePushToken(userId = admin.id, token = "story-recipient", platform = PushPlatform.ANDROID))
        doAnswer { invocation -> data += invocation.getArgument<Map<String, String>>(1).toMap(); null }
            .`when`(sender).sendToUser(anyLong(), anyMap(),
                any(com.google.firebase.messaging.AndroidConfig.Priority::class.java)
                    ?: com.google.firebase.messaging.AndroidConfig.Priority.NORMAL, isNull(), isNull(), anyString())
        doAnswer { invocation -> data += invocation.getArgument<PushMessage>(0).data.toMap(); 1 }
            .`when`(store).insert(any(PushMessage::class.java)
                ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = ""), any(Instant::class.java) ?: Instant.EPOCH)
    }

    private fun reserve(storyId: String? = null) = client.post().uri(root)
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("title" to "스토리 추천", "body" to "지금 만나보세요", "storyId" to storyId, "reason" to "스토리 알림 검증"))
        .exchange()

    private fun create(story: Story): JsonNode = mapper.readTree(reserve(story.publicId.toString())
        .expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `예약과 목록 상세 취소 재실행은 스토리 공개 UUID를 반환하고 DB에는 PK를 저장한다`() {
        val story = stories.saveAndFlush(Story(title = "공개 스토리", userId = admin.id))
        val created = create(story)
        assertThat(created["storyId"]?.asText()).isEqualTo(story.publicId.toString())
        val id = created["campaignId"].asText()
        assertThat(jdbc.queryForObject("SELECT story_id FROM push_campaigns WHERE public_id=?", Long::class.java, UUID.fromString(id)))
            .isEqualTo(story.id)
        for (path in listOf(root, "$root/$id")) {
            val prefix = if (path == root) "$.items[0]" else "$"
            client.get().uri(path).header("Authorization", "Bearer $token").exchange().expectStatus().isOk
                .expectBody().jsonPath("$prefix.storyId").isEqualTo(story.publicId.toString())
        }
        for (operation in listOf("cancel", "retry")) {
            if (operation == "retry") jdbc.update("UPDATE push_campaigns SET status='FAILED' WHERE public_id=?", UUID.fromString(id))
            client.post().uri("$root/$id/$operation").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "상태 전이 검증"))
                .exchange().expectStatus().isOk.expectBody().jsonPath("$.storyId").isEqualTo(story.publicId.toString())
        }
    }

    @Test fun `비공개 초안 삭제 미존재와 UUID 형식 오류는 예약과 감사 저장 전에 거절한다`() {
        val rejected = listOf(
            Story(title = "소유자 비공개", userId = admin.id, visibility = StoryVisibility.PRIVATE),
            Story(title = "게스트 비공개", visibility = StoryVisibility.PRIVATE),
            Story(title = "초안", userId = admin.id, status = StoryStatus.DRAFT),
            Story(title = "삭제", userId = admin.id, deletedAt = Instant.now()),
        ).map { stories.saveAndFlush(it).publicId.toString() } + listOf(UUID.randomUUID().toString(), "bad", "123")
        rejected.forEach { reserve(it).expectStatus().isBadRequest }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_campaigns", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `공개 스토리 알림은 발송 데이터에 UUID와 스토리 절대 딥링크를 싣는다`() {
        val story = stories.saveAndFlush(Story(title = "공개 스토리", userId = admin.id))
        val campaign = create(story)
        assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(1)
        assertThat(data.single()).containsEntry("storyId", story.publicId.toString())
            .containsEntry("deepLink", "https://manyak.app/stories/${story.publicId}?utm_source=push&utm_medium=push&utm_campaign=${campaign["campaignId"].asText()}&utm_content=promotion")
            .containsEntry("type", "PROMOTION").containsEntry("title", "(광고) 스토리 추천")
    }

    @Test fun `예약 뒤 비공개 초안 삭제로 바뀐 스토리는 발송 시 이동 정보를 제외하고 보낸다`() {
        for (change in listOf<(Story) -> Unit>(
            { it.visibility = StoryVisibility.PRIVATE }, { it.status = StoryStatus.DRAFT }, { it.deletedAt = Instant.now() },
        )) {
            val story = stories.saveAndFlush(Story(title = "공개 스토리", userId = admin.id))
            val campaignId = create(story)["campaignId"].asText()
            change(story)
            stories.saveAndFlush(story)
            data.clear()
            assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(1)
            assertThat(data.single()).doesNotContainKeys("storyId", "deepLink")
                .containsEntry("type", "PROMOTION").containsEntry("body", "지금 만나보세요")
            client.get().uri("$root/$campaignId").header("Authorization", "Bearer $token").exchange()
                .expectStatus().isOk.expectBody().jsonPath("$.storyId").isEqualTo(story.publicId.toString())
        }
    }

    @Test fun `이동 대상 없는 예약은 storyId null을 반환하고 링크 없이 발송한다`() {
        val created = mapper.readTree(reserve().expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(created.has("storyId")).isTrue()
        assertThat(created["storyId"].isNull).isTrue()
        assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(1)
        assertThat(data.single()).doesNotContainKeys("storyId", "deepLink")
    }
}
