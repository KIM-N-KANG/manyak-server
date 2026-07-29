package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryChatShareRepository
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
 * 채팅 공유 발급의 동시성 직렬화 검증(Codex P2, KNK-706).
 *
 * 발급은 채팅 행 비관적 쓰기 락으로 소유권 검사와 삽입을 묶는다. 같은 채팅에 동시 발급이 몰려도 락이
 * 이를 직렬화하므로, 뒤따르는 요청은 상대가 커밋한 공유를 조회로 발견해 같은 shareId를 돌려준다
 * (= `uq_story_chat_shares_chat_cutoff` 위반이 애초에 발생하지 않는다). 락을 걷어내면 이 테스트가
 * 중복 행 또는 유니크 위반으로 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ChatShareConcurrencyIntegrationTests {

    @Autowired private lateinit var chatService: ChatService
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var storyChatShareRepository: StoryChatShareRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `같은 채팅의 동시 공유 발급은 직렬화되어 공유가 하나만 생성된다`() {
        val story = storyRepository.save(Story(title = "동시 공유 발급 스토리", genre = "판타지"))
        val chat = storyChatRepository.save(StoryChat(storyId = story.id, currentTurn = 2))

        val threads = 4
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val shareIds = try {
            val futures = (1..threads).map {
                pool.submit<String> {
                    ready.countDown()
                    go.await()
                    chatService.createChatShare(chat.publicId.toString(), null).shareId
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            go.countDown() // 네 스레드 동시 출발
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 락 직렬화로 멱등이 성립한다: 네 요청이 모두 같은 shareId를 받고, 행은 하나만 남는다.
        assertThat(shareIds.toSet()).hasSize(1)
        assertThat(storyChatShareRepository.findAll().filter { it.chatId == chat.id }).hasSize(1)
        assertThat(storyChatShareRepository.findByChatIdAndTurnCutoff(chat.id, 2)).isNotNull
    }
}
