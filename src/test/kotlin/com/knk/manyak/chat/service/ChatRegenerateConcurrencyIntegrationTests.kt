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
 * regenerated_count 증가가 lost update 없이 정확히 2가 되는지(=대사 완료 수가 charge 수와 일치) 고정한다.
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
    fun `같은 마지막 턴의 동시 재생성은 직렬화되어 regenerated_count가 정확히 증가한다`() {
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
            val futures = (1..threads).map { i ->
                pool.submit {
                    ready.countDown()
                    go.await()
                    chatTurnPersister.regenerateLastTurn(
                        chatId = chat.id,
                        expectedAssistantId = lastAssistant.id,
                        aiOutput = "재생성 응답 $i",
                        choices = listOf("선택 $i-1", "선택 $i-2"),
                    )
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown() // 두 스레드 동시 출발
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 락 직렬화로 두 재생성이 각각 반영 → regenerated_count == 2 (lost update 없음).
        val reloaded = storyChatRepository.findById(chat.id).orElseThrow()
        assertThat(reloaded.regeneratedCount).isEqualTo(2)
        assertThat(reloaded.currentTurn).isEqualTo(1) // 턴 수는 불변
    }
}
