package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChatMainEvent
import com.knk.manyak.story.entity.UserStoryEndingReach
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/**
 * KNK-521(B5-B): 엔딩·주요 사건 런타임 상태 저장소 매핑·유니크 제약 검증.
 *
 * H2(ddl-auto)는 @UniqueConstraint를 생성하므로 유니크 위반은 검증되지만, FK ON DELETE·체크 제약은
 * Flyway 정본이라 gen-db-docs.sh 실 DB 경로로 검증한다(스펙 §테스트와 마이그레이션 검증).
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class EndingRuntimeStateRepositoryTests {

    @Autowired
    private lateinit var chatMainEventRepository: StoryChatMainEventRepository

    @Autowired
    private lateinit var endingReachRepository: UserStoryEndingReachRepository

    @BeforeEach
    fun setUp() {
        chatMainEventRepository.deleteAllInBatch()
        endingReachRepository.deleteAllInBatch()
    }

    @Test
    fun `채팅의 거쳐온 사건을 조회하고 존재 여부를 판정한다`() {
        chatMainEventRepository.save(StoryChatMainEvent(chatId = 1, mainEventId = 10))
        chatMainEventRepository.save(StoryChatMainEvent(chatId = 1, mainEventId = 11))
        chatMainEventRepository.save(StoryChatMainEvent(chatId = 2, mainEventId = 10))

        val forChat1 = chatMainEventRepository.findByChatId(1)
        assertEquals(setOf(10L, 11L), forChat1.map { it.mainEventId }.toSet())
        assertTrue(chatMainEventRepository.existsByChatIdAndMainEventId(1, 10))
        assertFalse(chatMainEventRepository.existsByChatIdAndMainEventId(1, 99))
    }

    @Test
    fun `같은 채팅에서 동일 사건을 중복 완결하면 UNIQUE 제약으로 거부된다`() {
        chatMainEventRepository.saveAndFlush(StoryChatMainEvent(chatId = 1, mainEventId = 10))

        assertThrows(DataIntegrityViolationException::class.java) {
            chatMainEventRepository.saveAndFlush(StoryChatMainEvent(chatId = 1, mainEventId = 10))
        }
    }

    @Test
    fun `회원의 스토리별 도달 엔딩을 조회하고 존재 여부를 이름으로 판정한다`() {
        endingReachRepository.save(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))
        endingReachRepository.save(reach(userId = 1, storyId = 100, name = "새드", endingId = 1001))
        endingReachRepository.save(reach(userId = 1, storyId = 200, name = "해피", endingId = 1000))
        endingReachRepository.save(reach(userId = 2, storyId = 100, name = "해피", endingId = 1000))

        val user1Story100 = endingReachRepository.findByUserIdAndStoryId(1, 100)
        assertEquals(setOf("해피", "새드"), user1Story100.map { it.endingNameSnapshot }.toSet())
        assertTrue(endingReachRepository.existsByUserIdAndStoryIdAndEndingNameSnapshot(1, 100, "해피"))
        assertFalse(endingReachRepository.existsByUserIdAndStoryIdAndEndingNameSnapshot(1, 100, "없는 엔딩"))
    }

    @Test
    fun `엔딩 id 없이 이름만으로도 도달을 기록한다`() {
        // 비공개 상태에서 엔딩이 교체된 뒤 도달하면 FK를 만족시킬 id가 없다. 이름만으로 집계가 성립해야 한다(V70).
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = null))

        assertTrue(endingReachRepository.existsByUserIdAndStoryIdAndEndingNameSnapshot(1, 100, "해피"))
    }

    @Test
    fun `동일 회원-스토리-엔딩 이름 도달을 중복 기록하면 UNIQUE 제약으로 거부된다`() {
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))

        assertThrows(DataIntegrityViolationException::class.java) {
            endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))
        }
    }

    @Test
    fun `이름이 같으면 엔딩 id가 달라도 UNIQUE 제약으로 거부된다`() {
        // contract 단계(V71)에서 유니크 키가 (user, story, ending_name_snapshot)으로 바뀌었다. 엔딩 교체로
        // id가 갈리거나 아예 없어도 같은 도달이면 DB가 막는다 — 이름이 정본 식별자라는 계약의 본체다.
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))

        assertThrows(DataIntegrityViolationException::class.java) {
            endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = null))
        }
    }

    private fun reach(userId: Long, storyId: Long, name: String, endingId: Long?) =
        UserStoryEndingReach(
            userId = userId,
            storyId = storyId,
            endingNameSnapshot = name,
            endingId = endingId,
        )
}
