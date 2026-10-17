package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 재생성 저장(교체)의 동시성 직렬화 검증(Codex P2, KNK-406).
 *
 * 같은 마지막 턴에 대한 동시 재생성 둘이 각자 마지막 턴 검사를 통과해도, 채팅 행 비관적 락으로 직렬화되어
 * regenerated_count 증가가 lost update 없이 정확히 1가 되는지(=대사 완료 수가 charge 수와 일치) 고정한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ChatRegenerateConcurrencyIntegrationTests {

    @Autowired private lateinit var chatTurnPersister: ChatTurnPersister
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyMessageRepository: StoryMessageRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `같은 시작 상태의 동시 재생성은 하나만 저장하고 다른 결과는 폐기한다`() {
        val story = storyRepository.save(Story(title = "동시 재생성 스토리", genre = "판타지"))
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 1))
        storyMessageRepository.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = 1))
        val lastAssistant = storyMessageRepository.save(
            StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "원본 응답", messageOrder = 2),
        )

        val threads = 2
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val outcomes = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val futures = (1..threads).map { i ->
                pool.submit {
                    ready.countDown()
                    go.await()
                    try {
                    chatTurnPersister.regenerateLastTurn(
                        chatId = chat.id,
                        expectedAssistantId = lastAssistant.id,
                        expectedState = GenerationStartState(lastAssistant.id, 0),
                        aiOutput = "재생성 응답 $i",
                        choices = listOf("선택 $i-1", "선택 $i-2"),
                    )
                    outcomes.add("saved")
                    } catch (ex: com.knk.manyak.global.error.CodedResponseStatusException) {
                        outcomes.add(ex.errorCode)
                    }
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown() // 두 스레드 동시 출발
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertThat(outcomes).containsExactlyInAnyOrder("saved", "CHAT_TURN_STALE")
        } finally {
            pool.shutdownNow()
        }

        // 채팅 락 안에서 같은 ID의 교체 상태도 재확인한다. 한 결과만 반영한다.
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.regeneratedCount).isEqualTo(1)
        assertThat(reloaded.currentTurn).isEqualTo(1) // 턴 수는 불변
    }
}
