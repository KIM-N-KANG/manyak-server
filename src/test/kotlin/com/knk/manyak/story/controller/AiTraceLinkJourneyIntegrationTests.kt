package com.knk.manyak.story.controller

import com.knk.manyak.chat.client.ChatChoicesResult
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.client.AiResponseMeta
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStoryCompileResponse
import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStoryMeta
import com.knk.manyak.story.client.AiStorySettings
import com.knk.manyak.story.client.AiStoryStartSettings
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import tools.jackson.databind.ObjectMapper

/**
 * KNK-751: 스토리라인 → 컴파일 → 채팅으로 이어지는 AI 호출이 **같은 creation_id**를 들고 가는지 고정한다.
 *
 * creation_id는 스토리라인 단계의 `story_creation_requests.request_id`다 — AI 호출 전에 커밋되고, 실패해도 남고,
 * 같은 requestId 재시도에 동일하다. 완성(compile) 단계 requestId나 세션 순차 PK와 혼동하면 여정이 끊기므로
 * 이 테스트가 세 호출의 값이 하나로 이어지는지를 검증한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:manyak-trace-journey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    ],
)
class AiTraceLinkJourneyIntegrationTests {

    class CapturingStoryAiClient : StoryAiClient {
        val storylineLink = AtomicReference<AiTraceLink>()
        val compileLink = AtomicReference<AiTraceLink>()

        override fun createStorylines(request: AiStorylinesRequest, traceLink: AiTraceLink): AiStorylinesResponse {
            storylineLink.set(traceLink)
            return AiStorylinesResponse(
                stories = (1..3).map { AiStoryItem(id = it, storyline = "스토리라인 $it", recommendedInfos = listOf("정보 $it")) },
                meta = AiResponseMeta(),
            )
        }

        override fun compileStory(request: AiStoryCompileRequest, traceLink: AiTraceLink): AiStoryCompileResponse {
            compileLink.set(traceLink)
            return AiStoryCompileResponse(
                stories = AiStoryMeta("생성된 스토리", "한 줄 소개", "설명"),
                storySettings = AiStorySettings("세계관", "캐릭터", "역할", "규칙"),
                storyStartSettings = AiStoryStartSettings("시작", "상황", "프롤로그"),
                storySuggestedInputs = listOf("추천1"),
                meta = AiResponseMeta(),
            )
        }
    }

    class CapturingChatTurnAiClient : ChatTurnAiClient {
        val turnLink = AtomicReference<AiTraceLink>()
        val turnRequest = AtomicReference<ChatTurnAiRequest>()

        override fun streamTurn(
            request: ChatTurnAiRequest,
            traceLink: AiTraceLink,
            onToken: (String) -> Unit,
        ): ChatTurnAiResult {
            turnLink.set(traceLink)
            turnRequest.set(request)
            onToken("응답")
            return ChatTurnAiResult(aiOutput = "응답 본문", choices = emptyList())
        }

        override fun generateChoices(
            request: ChatTurnAiRequest,
            aiOutput: String,
            traceLink: AiTraceLink,
        ): ChatChoicesResult = ChatChoicesResult(emptyList())
    }

    @TestConfiguration
    class CapturingAiClientConfig {
        @Bean
        @Primary
        fun capturingStoryAiClient(): CapturingStoryAiClient = CapturingStoryAiClient()

        @Bean
        @Primary
        fun capturingChatTurnAiClient(): CapturingChatTurnAiClient = CapturingChatTurnAiClient()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyAiClient: CapturingStoryAiClient
    @Autowired private lateinit var chatAiClient: CapturingChatTurnAiClient
    @Autowired private lateinit var tagRepository: StoryCreationTagRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `스토리라인·컴파일·채팅 턴이 같은 creation_id를 헤더 재료로 들고 간다`() {
        val storylineRequestId = UUID.randomUUID()
        val genreTag = savePredefinedGenreTag()

        // 1) 스토리라인 생성: creation_id는 이 요청의 requestId다.
        val storylines = postStorylines(storylineRequestId, genreTag.id)
        assertThat(storyAiClient.storylineLink.get().creationId).isEqualTo(storylineRequestId)

        // 2) 컴파일: 완성 요청은 **다른** requestId를 쓰지만, creation_id는 스토리라인 단계 값 그대로여야 한다.
        val compileRequestId = UUID.randomUUID()
        val storyPublicId = postSimpleStory(compileRequestId, storylines.simpleCreationId, storylines.secondStorylineId)
        val compileLink = storyAiClient.compileLink.get()
        assertThat(compileLink.creationId)
            .`as`("컴파일의 creation_id는 완성 requestId가 아니라 스토리라인 단계 requestId다")
            .isEqualTo(storylineRequestId)
        assertThat(compileLink.creationId).isNotEqualTo(compileRequestId)
        // 선택한 후보의 1-based 순서가 AI 응답 후보 id와 맞는다.
        assertThat(compileLink.storylineOrder?.toInt()).isEqualTo(2)
        // 스토리라인 id는 요청 본문에 이미 실려 있던 그 Long 그대로다(스펙 §4-4 — 임시 리소스라 소유 개념이 없다).
        assertThat(compileLink.storylineId).isEqualTo(storylines.secondStorylineId)

        // 3) 채팅 턴: 채팅 생성 시 1회 해석해 둔 creation_id가 그대로 실린다.
        val chatId = createChat(storyPublicId)
        stream(chatId, "다음 행동을 한다.")
        val turnLink = chatAiClient.turnLink.get()
        assertThat(turnLink.creationId).isEqualTo(storylineRequestId)
        assertThat(turnLink.chatId.toString()).isEqualTo(chatId)
        assertThat(turnLink.storyId.toString()).isEqualTo(storyPublicId)
        assertThat(turnLink.startSettingId).isNotNull()
        // 첫 턴의 예측 턴 번호는 current_turn(0) + 1이다. 권위값은 저장이 확정한다.
        assertThat(turnLink.turnNumber).isEqualTo(1)
        assertThat(turnLink.isRegenerated).isFalse()
    }

    @Test
    fun `재생성은 새 턴이 아니라 교체이므로 대상 턴 번호를 그대로 싣고 is_regenerated가 true다`() {
        val genreTag = savePredefinedGenreTag()
        val storylines = postStorylines(UUID.randomUUID(), genreTag.id)
        val storyPublicId = postSimpleStory(UUID.randomUUID(), storylines.simpleCreationId, storylines.secondStorylineId)
        val chatId = createChat(storyPublicId)
        val turnId = turnIdOf(stream(chatId, "다음 행동을 한다."))

        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/regenerate/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":$turnId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()

        val regeneratedLink = chatAiClient.turnLink.get()
        // 재생성은 current_turn을 늘리지 않으므로 예측치도 +1이 아니라 그 턴 번호(1) 그대로다.
        assertThat(regeneratedLink.turnNumber).isEqualTo(1)
        assertThat(regeneratedLink.isRegenerated).isTrue()
    }

    @Test
    fun `일반 제작 스토리로 시작한 채팅은 creation_id를 싣지 않는다`() {
        // 생성 세션이 없는 스토리(저작 경로)는 creation_id가 없어 헤더가 생략된다.
        val storyPublicId = createGeneralStory()
        val chatId = createChat(storyPublicId)

        stream(chatId, "다음 행동을 한다.")

        assertThat(chatAiClient.turnLink.get().creationId).isNull()
    }

    @Test
    fun `userSource는 허용값만 AI 바디로 통과하고 잘못된 값은 400이다`() {
        val storyPublicId = createGeneralStory()
        val chatId = createChat(storyPublicId)

        stream(chatId, "다음 행동을 한다.", userSource = "edited_choice")
        assertThat(chatAiClient.turnRequest.get().userSource).isEqualTo("edited_choice")

        restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"입력","userSource":"guessed"}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    private fun savePredefinedGenreTag(): StoryCreationTag =
        tagRepository.save(
            StoryCreationTag(
                category = SimpleStoryTagCategory.GENRE,
                name = "판타지",
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = 0,
                isActive = true,
            ),
        )

    private data class GeneratedStorylines(val simpleCreationId: Long, val secondStorylineId: Long)

    private fun postStorylines(requestId: UUID, tagId: Long): GeneratedStorylines {
        val body = restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"requestId":"$requestId","genreTagIds":[$tagId],"protagonist":{}}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: error("스토리라인 응답이 비어 있습니다.")
        val json = objectMapper.readTree(body)
        return GeneratedStorylines(
            simpleCreationId = json["simpleCreationId"].asLong(),
            // 2번째 후보를 고른다 — storyline_order(1-based)가 그대로 헤더에 실리는지 보기 위해.
            secondStorylineId = json["storylines"][1]["id"].asLong(),
        )
    }

    private fun postSimpleStory(requestId: UUID, simpleCreationId: Long, storylineId: Long): String {
        val body = restTestClient.post()
            .uri("/api/v1/stories/simple")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """{"requestId":"$requestId","simpleCreationId":$simpleCreationId,"storylineId":$storylineId,"additionalInfos":[]}""",
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: error("스토리 완성 응답이 비어 있습니다.")
        return objectMapper.readTree(body)["id"].asString()
    }

    /** 간편 제작을 거치지 않은(생성 세션 없는) 스토리를 만든다 — creation_id 생략 경로 재현용. */
    private fun createGeneralStory(): String {
        val storylineRequestId = UUID.randomUUID()
        val genreTag = savePredefinedGenreTag()
        val storylines = postStorylines(storylineRequestId, genreTag.id)
        val storyPublicId = postSimpleStory(UUID.randomUUID(), storylines.simpleCreationId, storylines.secondStorylineId)
        // 만들어진 세션에서 스토리 연결을 끊어, 저작 경로 스토리와 같은 상태(역조회 결과 없음)로 만든다.
        detachCreationSession()
        return storyPublicId
    }

    @Autowired private lateinit var creationSessionRepository: com.knk.manyak.story.repository.StoryCreationSessionRepository

    private fun detachCreationSession() {
        creationSessionRepository.findAll().forEach { session ->
            session.storyId = null
            creationSessionRepository.save(session)
        }
    }

    private fun createChat(storyPublicId: String): String {
        val body = restTestClient.post()
            .uri("/api/v1/chats")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"storyId":"$storyPublicId"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: error("채팅 생성 응답이 비어 있습니다.")
        return objectMapper.readTree(body)["id"].asString()
    }

    private fun stream(chatId: String, userInput: String, userSource: String? = null): String {
        val userSourceField = userSource?.let { ""","userSource":"$it"""" }.orEmpty()
        return restTestClient.post()
            .uri("/api/v1/chats/$chatId/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"$userInput"$userSourceField}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: error("스트리밍 응답 본문이 비어 있습니다.")
    }

    /** SSE completed 이벤트에서 저장된 턴 id를 뽑는다(재생성 요청 재료). */
    private fun turnIdOf(sse: String): Long =
        Regex(""""turnId":(\d+)""").find(sse)?.groupValues?.get(1)?.toLong()
            ?: error("completed 이벤트에 turnId가 없습니다: $sse")
}
