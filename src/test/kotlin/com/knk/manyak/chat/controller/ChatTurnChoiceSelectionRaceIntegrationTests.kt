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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 이어쓰기와 재생성이 겹칠 때 선택 기록이 오염되지 않는지 검증한다(KNK-819, Codex P2 지적).
 *
 * 재생성은 ASSISTANT 메시지 id를 **유지한 채** 본문만 교체하고 선택지를 지운다. 그래서 `sourceTurnId`가 여전히
 * 마지막 턴 id와 일치해, 순번만으로는 사용자가 본 선택지 세대와 재생성 후 새로 만들어진 세대를 구분할 수 없다.
 *
 * Codex가 묘사한 순서를 그대로 재현한다.
 *   1. 사용자가 O0[2]를 골라 이어쓰기를 보낸다(AI 호출 구간에는 DB 락도 트랜잭션도 없다).
 *   2. 그 사이 같은 턴에 재생성이 커밋된다(본문 교체 + O0 삭제, 메시지 id는 그대로).
 *   3. 프론트가 선택지 트리거를 불러 같은 message_id에 새 집합 O1이 들어간다.
 *   4. 이어쓰기의 persistTurn이 뒤늦게 돈다 — 가드가 없으면 O1[2]를 선택 처리하고, 사용자가 본 적 없는
 *      원문(O1[2])과 비교해 isEdited를 계산한다.
 *
 * 페이크 AI([GatedChatTurnAiClientConfig])가 이어쓰기 입력일 때만 대기해 2·3번이 그 사이에 끼어들게 한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@Import(GatedChatTurnAiClientConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatTurnChoiceSelectionRaceIntegrationTests {

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
        GatedChatTurnAiClientConfig.reset()
    }

    @AfterEach
    fun tearDown() {
        // 붙잡힌 호출이 남지 않도록 문을 열고 토글을 원복한다(다른 테스트 클래스와 컨텍스트를 공유하므로 필수).
        GatedChatTurnAiClientConfig.gate.countDown()
        GatedChatTurnAiClientConfig.reset()
    }

    @Test
    fun `이어쓰기 진행 중 재생성으로 갈아끼워진 선택지는 선택 처리되지 않는다`() {
        GatedChatTurnAiClientConfig.gatedInput = PICKED_TEXT
        GatedChatTurnAiClientConfig.nextChoices = O1

        val story = seedStory()
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "첫 입력", messageOrder = 1),
        )
        val assistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "첫 응답", messageOrder = 2),
        )
        O0.forEachIndexed { index, text ->
            storyChoiceRepository.save(
                StoryChoice(
                    chatId = chat.id,
                    messageId = assistant.id,
                    choiceText = text,
                    choiceOrder = (index + 1).toShort(),
                ),
            )
        }

        // 1. 사용자가 O0[2]를 골라 이어쓰기를 보낸다. 페이크 AI가 붙잡으므로 이 호출은 대기 상태로 남는다.
        val append = CompletableFuture.supplyAsync {
            restTestClient.post()
                .uri("/api/v1/chats/${chat.publicId}/turns/stream")
                .header(DEVICE_ID_HEADER, "race-device")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body("""{"userInput":"$PICKED_TEXT","sourceTurnId":${assistant.id},"choiceOrder":2}""")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
                .orEmpty()
        }
        assertThat(GatedChatTurnAiClientConfig.entered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            .describedAs("이어쓰기가 AI 호출 구간에 진입해야 한다")
            .isTrue()

        // 2. 그 사이 같은 턴을 재생성한다. 메시지 id는 유지되고 O0는 삭제된다.
        //    재생성은 직전 USER 입력("첫 입력")을 재전송하므로 페이크 AI에 붙잡히지 않는다.
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/regenerate/stream")
            .header(DEVICE_ID_HEADER, "race-device")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .body("""{"turnId":${assistant.id}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)).isEmpty()

        // 3. 프론트가 선택지 트리거를 불러 같은 message_id에 새 집합 O1이 들어간다.
        restTestClient.post()
            .uri("/api/v1/chats/${chat.publicId}/turns/${assistant.id}/choices")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk
        assertThat(storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id).map { it.choiceText })
            .isEqualTo(O1)

        // 4. 이제 이어쓰기의 AI 호출을 풀어 persistTurn이 돌게 한다.
        GatedChatTurnAiClientConfig.gate.countDown()
        append.get(AWAIT_SECONDS, TimeUnit.SECONDS)

        // 이어쓰기 턴 자체는 정상 저장된다(거절 금지 계약).
        assertThat(storyChatRepository.findById(chat.id).orElseThrow().currentTurn).isEqualTo(2)

        // 핵심: 사용자가 본 적 없는 O1 행은 어느 것도 선택 처리되지 않아야 한다.
        // 가드가 없으면 choiceOrder=2가 여전히 일치해 O1[2]("옆 사람의 팔을 붙잡는다.")가 선택되고,
        // isEdited는 사용자가 실제로 보낸 O0[2]와 비교돼 true로 오판된다.
        storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id).forEach { choice ->
            assertThat(choice.isSelected)
                .describedAs("재생성으로 갈아끼워진 선택지 '${choice.choiceText}'가 선택 처리됐다")
                .isFalse()
            assertThat(choice.selectedAt).isNull()
            assertThat(choice.isEdited).isNull()
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

    private companion object {
        const val DEVICE_ID_HEADER = "X-Manyak-Device-Id"
        const val AWAIT_SECONDS = 20L

        /** 사용자가 고른 선택지 원문(= O0[2]). 페이크 AI는 이 입력일 때만 대기한다. */
        const val PICKED_TEXT = "한 걸음 앞으로 나선다."

        val O0 = listOf("주변 사람들의 반응을 살핀다.", PICKED_TEXT, "조용히 자리를 벗어난다.")

        /** 재생성된 본문 기준으로 새로 생성되는 선택지 집합. O0와 겹치는 문장이 없어야 오염이 드러난다. */
        val O1 = listOf("무너진 천장을 올려다본다.", "옆 사람의 팔을 붙잡는다.", "출구 쪽으로 달린다.")
    }
}
