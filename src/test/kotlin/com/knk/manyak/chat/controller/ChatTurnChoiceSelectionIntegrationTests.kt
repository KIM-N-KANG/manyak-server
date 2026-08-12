package com.knk.manyak.chat.controller

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 채팅 선택지 선택 결과 기록(KNK-819, 스펙 §4-3-3) 통합 검증.
 *
 * 턴 요청의 `sourceTurnId`·`choiceOrder`로 직전 턴의 선택지를 특정해 `is_selected`·`selected_at`·`is_edited`를
 * 같은 턴 저장 트랜잭션에서 기록한다. 두 필드는 선택이며 **어떤 값이 와도 요청을 거절하지 않는다** — 값이 없거나,
 * 마지막 턴이 아니거나, 순번이 범위 밖이면 기록만 건너뛰고 턴은 정상 저장된다.
 *
 * 특히 `choiceOrder = 1`(첫 선택지)이 기록되는지를 별도로 고정한다 — 인덱스 산술로 구현하면 0-based 실수가
 * 조용히 첫 선택지를 어긋나게 만든다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatTurnChoiceSelectionIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storySettingRepository: StorySettingRepository

    @Autowired
    private lateinit var storyStartSettingRepository: StoryStartSettingRepository

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var storyMessageRepository: StoryMessageRepository

    @Autowired
    private lateinit var storyChoiceRepository: StoryChoiceRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `첫 번째 선택지를 골라도 그 행에만 선택이 기록된다`() {
        // 0-based 실수 가드: choiceOrder = 1이 배열 인덱스 1(두 번째 선택지)로 새면 이 단언이 깨진다.
        val fixture = seedChatWithOneTurn()

        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[0], sourceTurnId = fixture.assistantId, choiceOrder = 1)

        val choices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(fixture.assistantId)
        assertThat(choices[0].isSelected).isTrue()
        assertThat(choices[0].selectedAt).isNotNull()
        assertThat(choices[0].isEdited).isFalse()
        // 나머지 두 행은 세 컬럼 모두 손대지 않은 상태여야 한다 — 고르지 않은 선택지는 편집 여부 자체가 없다(NULL).
        listOf(choices[1], choices[2]).forEach { untouched ->
            assertThat(untouched.isSelected).isFalse()
            assertThat(untouched.selectedAt).isNull()
            assertThat(untouched.isEdited).isNull()
        }
    }

    @Test
    fun `마지막 순번 선택지도 기록된다`() {
        val fixture = seedChatWithOneTurn()

        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[2], sourceTurnId = fixture.assistantId, choiceOrder = 3)

        val choices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(fixture.assistantId)
        assertThat(choices[2].isSelected).isTrue()
        assertThat(choices[0].isSelected).isFalse()
        assertThat(choices[1].isSelected).isFalse()
    }

    @Test
    fun `선택지 원문 그대로 보내면 is_edited가 false다`() {
        val fixture = seedChatWithOneTurn()

        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[1], sourceTurnId = fixture.assistantId, choiceOrder = 2)

        assertThat(selectedChoice(fixture.assistantId).isEdited).isFalse()
    }

    @Test
    fun `앞뒤 공백과 개행만 다르면 is_edited가 false다`() {
        val fixture = seedChatWithOneTurn()
        // 원문의 공백 하나를 개행으로 바꾸고 앞뒤에 공백을 덧댄다. 정규화(NFC → trim → 공백 런 축약)가 흡수해야 한다.
        val whitespaceOnlyEdit = "  ${CHOICE_TEXTS[1].replaceFirst(" ", "\n")}  "

        streamTurn(fixture.chatPublicId, whitespaceOnlyEdit, sourceTurnId = fixture.assistantId, choiceOrder = 2)

        assertThat(selectedChoice(fixture.assistantId).isEdited).isFalse()
    }

    @Test
    fun `본문을 고쳐 보내면 is_edited가 true다`() {
        val fixture = seedChatWithOneTurn()

        streamTurn(
            fixture.chatPublicId,
            "${CHOICE_TEXTS[1]} 그리고 뒤를 돌아본다.",
            sourceTurnId = fixture.assistantId,
            choiceOrder = 2,
        )

        assertThat(selectedChoice(fixture.assistantId).isEdited).isTrue()
    }

    @Test
    fun `구두점만 바꿔도 편집으로 기록한다`() {
        // 구두점은 정규화에서 무시하지 않는다(스펙 §4-3-3) — 지우면 서로 다른 선택지가 같아질 수 있다.
        val fixture = seedChatWithOneTurn()

        streamTurn(
            fixture.chatPublicId,
            CHOICE_TEXTS[1].replace(".", "!"),
            sourceTurnId = fixture.assistantId,
            choiceOrder = 2,
        )

        assertThat(selectedChoice(fixture.assistantId).isEdited).isTrue()
    }

    @Test
    fun `선택 정보를 보내지 않아도 턴이 저장되고 선택 기록은 남지 않는다`() {
        // 구버전 클라이언트 호환: 두 필드가 없는 요청이 그대로 동작해야 한다.
        val fixture = seedChatWithOneTurn()

        streamTurn(fixture.chatPublicId, "직접 입력한 문장이다.")

        assertTurnPersisted(fixture, expectedTurnCount = 2)
        assertNoSelectionRecorded(fixture.assistantId)
    }

    @Test
    fun `다른 채팅의 턴 ID를 보내면 양쪽 채팅 모두 선택 기록이 남지 않는다`() {
        val fixture = seedChatWithOneTurn()
        val other = seedChatWithOneTurn()

        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[0], sourceTurnId = other.assistantId, choiceOrder = 1)

        assertTurnPersisted(fixture, expectedTurnCount = 2)
        assertNoSelectionRecorded(fixture.assistantId)
        // 남의 채팅 선택지를 대신 기록하는 교차 오염이 없어야 한다.
        assertNoSelectionRecorded(other.assistantId)
    }

    @Test
    fun `범위 밖 순번은 400이 아니라 기록만 건너뛴다`() {
        // 0(0-based 클라이언트)·4(개수 초과)·-1(쓰레기 값) 모두 요청을 거절하지 않는다.
        listOf(0, 4, -1).forEach { outOfRange ->
            databaseCleaner.cleanAll()
            val fixture = seedChatWithOneTurn()

            streamTurn(fixture.chatPublicId, CHOICE_TEXTS[0], sourceTurnId = fixture.assistantId, choiceOrder = outOfRange)

            assertTurnPersisted(fixture, expectedTurnCount = 2)
            assertNoSelectionRecorded(fixture.assistantId)
        }
    }

    @Test
    fun `마지막 턴이 아닌 sourceTurnId는 기록을 건너뛴다`() {
        val fixture = seedChatWithTwoTurns()

        // 첫 턴(이제 마지막이 아님)의 선택지를 지목한다.
        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[0], sourceTurnId = fixture.staleAssistantId, choiceOrder = 1)

        assertNoSelectionRecorded(fixture.staleAssistantId)
        assertThat(storyChatRepository.findById(fixture.chatId).orElseThrow().currentTurn).isEqualTo(3)
    }

    @Test
    fun `선택지가 아직 없는 턴을 지목해도 턴 저장은 성공한다`() {
        // 선택지 생성 트리거(POST /turns/{turnId}/choices)가 아직 안 돌았거나 실패한 상태.
        val fixture = seedChatWithOneTurn(withChoices = false)

        streamTurn(fixture.chatPublicId, "무언가 입력한다.", sourceTurnId = fixture.assistantId, choiceOrder = 1)

        assertTurnPersisted(fixture, expectedTurnCount = 2)
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(fixture.assistantId)).isEmpty()
    }

    @Test
    fun `sourceTurnId나 choiceOrder 한쪽만 보내면 기록을 건너뛴다`() {
        val onlyTurnId = seedChatWithOneTurn()
        streamTurn(onlyTurnId.chatPublicId, CHOICE_TEXTS[0], sourceTurnId = onlyTurnId.assistantId)
        assertTurnPersisted(onlyTurnId, expectedTurnCount = 2)
        assertNoSelectionRecorded(onlyTurnId.assistantId)

        val onlyOrder = seedChatWithOneTurn()
        streamTurn(onlyOrder.chatPublicId, CHOICE_TEXTS[0], choiceOrder = 1)
        assertTurnPersisted(onlyOrder, expectedTurnCount = 2)
        assertNoSelectionRecorded(onlyOrder.assistantId)
    }

    @Test
    fun `기록된 선택은 다음 턴을 재생성해도 그대로 유지된다`() {
        // 설계 전제(검수 반증 B): 선택 기록이 새 턴 저장과 같은 트랜잭션에서 직전 턴에 붙으므로,
        // 재생성의 deleteAll 대상은 언제나 "아직 선택되지 않은 마지막 턴"의 선택지뿐이다.
        val fixture = seedChatWithOneTurn()
        streamTurn(fixture.chatPublicId, CHOICE_TEXTS[1], sourceTurnId = fixture.assistantId, choiceOrder = 2)

        val newAssistantId = storyMessageRepository.findFirstByChatIdOrderByMessageOrderDesc(fixture.chatId)!!.id
        // 상태 조회는 재생성 저장이 끝난 뒤여야 한다. SSE body를 completed까지 소비해 비동기 저장 완료를 기다린다
        // — 상태 코드만 보면 스트림이 열린 시점에 반환돼 조회가 저장을 앞지를 수 있다(플래키).
        val regenerateBody = restTestClient.post()
            .uri("/api/v1/chats/${fixture.chatPublicId}/turns/regenerate/stream")
            .header(DEVICE_ID_HEADER, "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":$newAssistantId}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("재생성 스트리밍 응답 본문이 비어 있습니다.")
        assertThat(regenerateBody).contains("completed")

        val recorded = selectedChoice(fixture.assistantId)
        assertThat(recorded.choiceOrder.toInt()).isEqualTo(2)
        assertThat(recorded.isSelected).isTrue()
        assertThat(recorded.isEdited).isFalse()
        assertThat(recorded.selectedAt).isNotNull()
    }

    // --- 헬퍼 ---

    private fun assertTurnPersisted(fixture: ChatFixture, expectedTurnCount: Int) {
        assertThat(storyChatRepository.findById(fixture.chatId).orElseThrow().currentTurn).isEqualTo(expectedTurnCount)
    }

    private fun assertNoSelectionRecorded(assistantId: Long) {
        storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistantId).forEach {
            assertThat(it.isSelected).isFalse()
            assertThat(it.selectedAt).isNull()
            assertThat(it.isEdited).isNull()
        }
    }

    private fun selectedChoice(assistantId: Long): StoryChoice =
        storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistantId).single { it.isSelected }

    private fun streamTurn(
        chatPublicId: String,
        userInput: String,
        sourceTurnId: Long? = null,
        choiceOrder: Int? = null,
    ): String {
        val fields = buildList {
            add(""""userInput":${jsonString(userInput)}""")
            sourceTurnId?.let { add(""""sourceTurnId":$it""") }
            choiceOrder?.let { add(""""choiceOrder":$it""") }
        }
        return restTestClient.post()
            .uri("/api/v1/chats/$chatPublicId/turns/stream")
            .header(DEVICE_ID_HEADER, "test-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body(fields.joinToString(",", prefix = "{", postfix = "}"))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("스트리밍 응답 본문이 비어 있습니다.")
    }

    /** 테스트 본문에 개행·따옴표가 들어가므로 JSON 문자열로 escape한다. */
    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun seedChatWithOneTurn(withChoices: Boolean = true): ChatFixture {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1),
        )
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        if (withChoices) {
            saveChoices(chat.id, assistant.id)
        }
        return ChatFixture(chatId = chat.id, chatPublicId = chat.publicId.toString(), assistantId = assistant.id)
    }

    private fun seedChatWithTwoTurns(): ChatFixture {
        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1),
        )
        val stale = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "둘째 입력", messageOrder = 3),
        )
        val last = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "둘째 응답", messageOrder = 4),
        )
        saveChoices(chat.id, stale.id)
        saveChoices(chat.id, last.id)
        return ChatFixture(
            chatId = chat.id,
            chatPublicId = chat.publicId.toString(),
            assistantId = last.id,
            staleAssistantId = stale.id,
        )
    }

    private fun saveChoices(chatId: Long, messageId: Long) {
        CHOICE_TEXTS.forEachIndexed { index, text ->
            storyChoiceRepository.save(
                StoryChoice(
                    chatId = chatId,
                    messageId = messageId,
                    choiceText = text,
                    choiceOrder = (index + 1).toShort(),
                ),
            )
        }
    }

    private fun seedStory(): Story {
        val story = storyRepository.save(Story(title = "호아킨 아카데미의 무속성 신입생", genre = "판타지"))
        storySettingRepository.save(
            StorySetting(
                story = story,
                worldSetting = "마법 아카데미가 존재하는 세계",
                characterSetting = "강진우, 무속성 판정을 받은 1학년",
                userRoleSetting = "무속성 신입생",
                ruleSetting = "마법은 속성 발현으로만 사용한다.",
            ),
        )
        storyStartSettingRepository.save(
            StoryStartSetting(
                story = story,
                name = "강진우",
                prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다.",
                startSituation = "적성 검사가 시작된다.",
            ),
        )
        return story
    }

    private data class ChatFixture(
        val chatId: Long,
        val chatPublicId: String,
        val assistantId: Long,
        val staleAssistantId: Long = 0,
    )

    private companion object {
        const val DEVICE_ID_HEADER = "X-Manyak-Device-Id"

        val CHOICE_TEXTS = listOf(
            "주변 사람들의 반응을 살핀다.",
            "한 걸음 앞으로 나선다.",
            "조용히 자리를 벗어난다.",
        )
    }
}
