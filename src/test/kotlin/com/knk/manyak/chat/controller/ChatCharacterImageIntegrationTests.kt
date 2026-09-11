package com.knk.manyak.chat.controller

import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCharacterImage
import com.knk.manyak.story.repository.StoryCharacterImageRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * KNK-943: 채팅 인물 이미지 전달.
 *
 * - 채팅 턴 AI 요청에 `story_characters`의 인물-URL 매핑을 싣는다(이미지 없는 인물은 제외).
 * - AI가 스트리밍 중 보내는 `character_image` 이벤트를 프론트에 그대로 중계한다.
 *
 * AI 클라이언트는 공용 페이크([GatedChatTurnAiClientConfig])를 @Import해 쓴다 — 중첩 @TestConfiguration은
 * 클래스마다 Spring 컨텍스트를 하나씩 늘려 캐시 상한 축출로 이어진다(KNK-686, SpringContextBudgetGuardTests).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@Import(GatedChatTurnAiClientConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatCharacterImageIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyCharacterRepository: StoryCharacterRepository
    @Autowired private lateinit var storyCharacterImageRepository: StoryCharacterImageRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyMessageRepository: StoryMessageRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        GatedChatTurnAiClientConfig.reset()
        databaseCleaner.cleanAll()
    }

    @AfterEach
    fun tearDown() {
        GatedChatTurnAiClientConfig.reset()
    }

    @Test
    fun `채팅 요청에 이미지가 있는 인물만 매핑으로 싣는다`() {
        val story = seedStory()
        // 이미지 생성에 실패한 인물(image_url NULL)은 매핑에서 제외한다 — AI가 URL 없는 태그를 만들 수 없어야 한다.
        seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        seedCharacter(story, "이서연", null)
        seedCharacter(story, "박도윤", "https://cdn.test/characters/generated/s/c.webp")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        stream(chat, "문을 연다.")

        val mappings = GatedChatTurnAiClientConfig.lastRequest!!.characterImages
        assertThat(mappings.map { it.name }).containsExactly("강진우", "박도윤")
        assertThat(mappings.map { it.imageUrl }).containsExactly(
            "https://cdn.test/characters/generated/s/a.webp",
            "https://cdn.test/characters/generated/s/c.webp",
        )
    }

    @Test
    fun `인물 이미지가 여러 장이면 전부 이름과 함께 싣는다`() {
        // KNK-1126: 정본이 story_character_images로 옮겨가 인물당 여러 장을 싣는다. 같은 name의 항목이
        // 여러 개이고 image_name이 그것을 가른다(고르는 판정은 AI 몫 — KNK-1199).
        val story = seedStory()
        val character = seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        storyCharacterImageRepository.save(
            StoryCharacterImage(
                character = character,
                imageName = "강진우_웃음",
                imageUrl = "https://cdn.test/characters/uploaded/s/smile.webp",
                sortOrder = 1,
            ),
        )
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        stream(chat, "문을 연다.")

        val mappings = GatedChatTurnAiClientConfig.lastRequest!!.characterImages
        assertThat(mappings.map { it.name }).containsExactly("강진우", "강진우")
        assertThat(mappings.map { it.imageName }).containsExactly("강진우_기본", "강진우_웃음")
    }

    @Test
    fun `검수를 통과하지 못한 인물 이미지는 AI에게 보내지 않는다`() {
        val story = seedStory()
        val character = seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        storyCharacterImageRepository.save(
            StoryCharacterImage(
                character = character,
                imageName = "강진우_대기",
                imageUrl = "https://cdn.test/characters/uploaded/s/pending.webp",
                sortOrder = 1,
                moderationStatus = ImageModerationStatus.PENDING,
            ),
        )
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        stream(chat, "문을 연다.")

        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.characterImages.map { it.imageName })
            .containsExactly("강진우_기본")
    }

    @Test
    fun `인물이 없거나 이미지가 전부 없으면 빈 배열을 싣는다`() {
        val story = seedStory()
        seedCharacter(story, "이서연", null)
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        stream(chat, "문을 연다.")

        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.characterImages).isEmpty()
    }

    @Test
    fun `재생성 요청에도 같은 매핑을 싣는다`() {
        val story = seedStory()
        seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "문을 연다.", messageOrder = 1),
        )
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "문이 열렸다.", messageOrder = 2),
        )

        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":${assistant.id}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()

        assertThat(GatedChatTurnAiClientConfig.lastRequest!!.characterImages.map { it.name }).containsExactly("강진우")
    }

    @Test
    fun `AI의 character_image 이벤트를 프론트에 그대로 중계한다`() {
        val story = seedStory()
        seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))
        GatedChatTurnAiClientConfig.nextCharacterImage = ChatCharacterImageEvent(
            name = "강진우",
            imageUrl = "https://cdn.test/characters/generated/s/a.webp",
        )

        val body = stream(chat, "문을 연다.")

        assertThat(body).contains("event:character_image")
        assertThat(body).contains("\"name\":\"강진우\"")
        // 프론트 계약 키는 imageUrl(camelCase)이다 — 이 스트림의 다른 이벤트와 표기를 맞췄고 AI 실물과도 같다.
        assertThat(body).contains("\"imageUrl\":\"https://cdn.test/characters/generated/s/a.webp\"")
        assertThat(body).doesNotContain("image_url")
        // 본문 토큰·완료 계약은 그대로다(인물 이미지는 본문에 마커를 남기지 않는다).
        assertThat(body).contains("event:token")
        assertThat(body).contains("event:completed")
        assertThat(body).contains("\"aiOutput\"")
    }

    @Test
    fun `스트림이 중단된 뒤에는 인물 이미지 이벤트를 내보내지 않는다`() {
        // 클라이언트가 끊겨 워커 스레드가 인터럽트된 뒤에는 토큰과 마찬가지로 이미지 이벤트도 보내지 않는다.
        // (끊긴 emitter에 계속 쓰면 예외가 나 저장 경로까지 흔든다.)
        val story = seedStory()
        seedCharacter(story, "강진우", "https://cdn.test/characters/generated/s/a.webp")
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))
        GatedChatTurnAiClientConfig.nextCharacterImage = ChatCharacterImageEvent(
            name = "강진우",
            imageUrl = "https://cdn.test/characters/generated/s/a.webp",
        )
        GatedChatTurnAiClientConfig.interruptBeforeCharacterImage = true

        val body = stream(chat, "문을 연다.")

        assertThat(body).doesNotContain("character_image")
        // 인터럽트를 지운 뒤 이어지는 저장·완료 계약은 그대로다.
        assertThat(body).contains("event:completed")
    }

    @Test
    fun `AI가 character_image를 보내지 않으면 이벤트도 나가지 않는다`() {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id))

        val body = stream(chat, "문을 연다.")

        assertThat(body).doesNotContain("character_image")
        assertThat(body).contains("event:completed")
    }

    private fun seedStory(): Story = storyRepository.save(Story(title = "인물 이미지 스토리", genre = "판타지"))

    /**
     * 인물과 그 인물의 이미지 한 장을 심는다. 정본이 `story_character_images`로 옮겨가(KNK-1126, V76)
     * 컴파일이 만든 첫 장의 이름 규칙(`{이름}_기본`)을 그대로 쓴다. [imageUrl]이 null이면 이미지 없는 인물이다.
     */
    private fun seedCharacter(story: Story, name: String, imageUrl: String?): StoryCharacter {
        val character = storyCharacterRepository.save(StoryCharacter(story = story, name = name, imageUrl = imageUrl))
        imageUrl?.let {
            storyCharacterImageRepository.save(
                StoryCharacterImage(
                    character = character,
                    imageName = StoryCharacterImage.defaultImageNameOf(name),
                    imageUrl = it,
                ),
            )
        }
        return character
    }

    private fun stream(chat: StoryChat, userInput: String): String =
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/stream")
            .header("X-Manyak-Device-Id", "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"userInput":"$userInput"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")
}
