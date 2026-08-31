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
    fun `동일 회원-스토리-엔딩id 도달을 중복 기록하면 UNIQUE 제약으로 거부된다`() {
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))

        // 확장 단계(V70)에서는 **옛 유니크(user, story, ending_id)** 가 그대로 남아 id가 있는 행을 막는다.
        assertThrows(DataIntegrityViolationException::class.java) {
            endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))
        }
    }

    @Test
    fun `확장 단계에서는 이름이 같아도 id가 다르면 DB가 막지 않는다`() {
        // 이름 유니크는 중복 정리가 선행돼야 만들 수 있어 후속 티켓 몫이다(V70 주석). 그때까지 이름 기준
        // 중복 방지는 앱(EndingReachRecorder)이 맡고, DB는 옛 유니크만 든다. **이 전이 상태를 고정해 둔다** —
        // 후속 티켓이 이름 유니크를 넣으면 이 테스트가 빨개져서 함께 고쳐야 할 자리를 가리킨다.
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = 1000))
        endingReachRepository.saveAndFlush(reach(userId = 1, storyId = 100, name = "해피", endingId = null))

        assertEquals(2, endingReachRepository.findByUserIdAndStoryId(1, 100).size)
    }

    @Test
    fun `이름이 비어 있는 구버전 스타일 행도 저장된다`() {
        // 롤링 배포 창의 구버전 태스크는 이름 컬럼을 모르고 INSERT한다. NOT NULL이었다면 여기서 터졌다.
        endingReachRepository.saveAndFlush(
            UserStoryEndingReach(userId = 1, storyId = 100, endingNameSnapshot = null, endingId = 1000),
        )

        assertEquals(1, endingReachRepository.findByUserIdAndStoryId(1, 100).size)
    }

    private fun reach(userId: Long, storyId: Long, name: String, endingId: Long?) =
        UserStoryEndingReach(
            userId = userId,
            storyId = storyId,
            endingNameSnapshot = name,
            endingId = endingId,
        )
}
