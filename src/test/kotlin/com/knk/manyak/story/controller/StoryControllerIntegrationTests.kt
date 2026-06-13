package com.knk.manyak.story.controller

import com.knk.manyak.story.client.AiStoryItem
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.AiStorylinesResponse
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationExampleQuestionRepository
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
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
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.transaction.support.TransactionSynchronizationManager

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoryControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var tagRepository: StoryCreationTagRepository

    @Autowired
    private lateinit var sessionRepository: StoryCreationSessionRepository

    @Autowired
    private lateinit var sessionTagRepository: StoryCreationSessionTagRepository

    @Autowired
    private lateinit var exampleRepository: StoryCreationExampleRepository

    @Autowired
    private lateinit var questionRepository: StoryCreationExampleQuestionRepository

    @Autowired
    private lateinit var storyAiClient: CapturingStoryAiClient

    @BeforeEach
    fun setUp() {
        questionRepository.deleteAll()
        exampleRepository.deleteAll()
        sessionTagRepository.deleteAll()
        sessionRepository.deleteAll()
        tagRepository.deleteAll()
        storyAiClient.reset()
    }

    @Test
    fun `간편 제작 태그 목록을 조회한다`() {
        seedTag(SimpleStoryTagCategory.SUPPORTING_CHARACTER, "비밀스러운 조력자", 10)
        seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)
        tagRepository.save(
            StoryCreationTag(
                tagType = SimpleStoryTagCategory.GENRE,
                name = "사용자 입력",
                tagSource = StoryCreationTagSource.CUSTOM,
            ),
        )

        restTestClient.get()
            .uri("/api/v1/stories/simple/tags")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].name").isEqualTo("판타지")
            .jsonPath("$[0].category").isEqualTo("GENRE")
            .jsonPath("$[1].name").isEqualTo("기억상실")
            .jsonPath("$[1].category").isEqualTo("PROTAGONIST")
            .jsonPath("$[2].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$[2].category").isEqualTo("SUPPORTING_CHARACTER")
    }

    @Test
    fun `선택 태그로 스토리라인을 생성한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        val protagonist = seedTag(SimpleStoryTagCategory.PROTAGONIST, "기억상실", 10)

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "selectedTagIds": [${genre.id}, ${protagonist.id}]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.simpleCreationId").isNumber
            .jsonPath("$.selectedTags.length()").isEqualTo(2)
            .jsonPath("$.selectedTags[0].name").isEqualTo("판타지")
            .jsonPath("$.storylines.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].story").isEqualTo("생성 스토리 1")
            .jsonPath("$.storylines[0].helpQuestions.length()").isEqualTo(3)
            .jsonPath("$.storylines[0].helpQuestions[0].question").isEqualTo("질문 1-1")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(storyAiClient.transactionActiveDuringCall == false)
        check(aiRequest.genre_tags == listOf("판타지"))
        check(aiRequest.protagonist_tags == listOf("기억상실"))
        check(aiRequest.supporting_tags.isEmpty())
        check(exampleRepository.count() == 3L)
        check(questionRepository.count() == 9L)
    }

    @Test
    fun `직접 추가 태그만으로 스토리라인을 생성한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
                {
                  "customTags": [
                    {
                      "name": "비밀스러운 조력자",
                      "category": "SUPPORTING_CHARACTER"
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.selectedTags.length()").isEqualTo(1)
            .jsonPath("$.selectedTags[0].name").isEqualTo("비밀스러운 조력자")
            .jsonPath("$.selectedTags[0].category").isEqualTo("SUPPORTING_CHARACTER")

        val aiRequest = storyAiClient.lastRequest
        requireNotNull(aiRequest)
        check(aiRequest.supporting_tags == listOf("비밀스러운 조력자"))
    }

    @Test
    fun `태그가 없으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[],"customTags":[]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("요청 값이 올바르지 않습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
            .jsonPath("$.details.length()").isNumber
    }

    @Test
    fun `존재하지 않는 선택 태그가 있으면 스토리라인 생성 요청을 거절한다`() {
        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[999999]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.code").isEqualTo("BAD_REQUEST")
            .jsonPath("$.message").isEqualTo("사용할 수 없는 태그 ID가 포함되어 있습니다: 999999")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    @Test
    fun `AI 서버 오류는 Bad Gateway로 응답한다`() {
        val genre = seedTag(SimpleStoryTagCategory.GENRE, "판타지", 10)
        storyAiClient.fail = true

        restTestClient.post()
            .uri("/api/v1/stories/simple/storylines")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"selectedTagIds":[${genre.id}]}""")
            .exchange()
            .expectStatus().isEqualTo(502)
            .expectBody()
            .jsonPath("$.status").isEqualTo(502)
            .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
            .jsonPath("$.message").isEqualTo("AI 스토리라인 생성 요청에 실패했습니다.")
            .jsonPath("$.path").isEqualTo("/api/v1/stories/simple/storylines")
    }

    private fun seedTag(
        category: SimpleStoryTagCategory,
        name: String,
        sortOrder: Int,
    ): StoryCreationTag =
        tagRepository.save(
            StoryCreationTag(
                tagType = category,
                name = name,
                tagSource = StoryCreationTagSource.PREDEFINED,
                sortOrder = sortOrder,
            ),
        )

    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun storyAiClient(): CapturingStoryAiClient = CapturingStoryAiClient()
    }

    class CapturingStoryAiClient : StoryAiClient {
        var lastRequest: AiStorylinesRequest? = null
            private set
        var fail: Boolean = false
        var transactionActiveDuringCall: Boolean? = null
            private set

        override fun createStorylines(request: AiStorylinesRequest): AiStorylinesResponse {
            lastRequest = request
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive()
            if (fail) {
                throw IllegalStateException("AI failure")
            }

            return AiStorylinesResponse(
                stories = (1..3).map { index ->
                    AiStoryItem(
                        id = index,
                        story = "생성 스토리 $index",
                        questions = (1..3).map { questionIndex -> "질문 $index-$questionIndex" },
                    )
                },
            )
        }

        fun reset() {
            lastRequest = null
            fail = false
            transactionActiveDuringCall = null
        }
    }
}
