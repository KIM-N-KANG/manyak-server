package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.context.event.ApplicationEvents
import java.time.Duration
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest
@RecordApplicationEvents
class SubmissionRetryIntegrationTests {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var rows: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var transactions: SubmissionTransactions
    @Autowired lateinit var claims: SubmissionClaimStore
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var events: ApplicationEvents
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var notifier: SlackSubmissionHeldNotifier
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    private val lease = Duration.ofSeconds(300)
    @BeforeEach fun clean() = cleaner.cleanAll()
    private fun submit(): StorySubmission {
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        return rows.findAll().single()
    }
    private fun result(code: String) = ModerationResult("REJECTED", emptyList(), code)
    private fun count(id: Long) = jdbc.queryForObject("select retry_count from story_submissions where id = ?", Int::class.java, id)
    private fun due(id: Long) = jdbc.queryForObject("select next_attempt_at from story_submissions where id = ?", java.sql.Timestamp::class.java, id)!!.toInstant()

    @ParameterizedTest @ValueSource(strings = ["MODEL_CALL_FAILED", "IMAGE_DOWNLOAD_FAILED", "MODERATION_UNAVAILABLE"])
    fun `일시 실패는 1분 5분 뒤 재시도하고 세번째 실패는 보류한다`(code: String) {
        val row = submit()
        var work = claims.claim(Instant.now(), lease, 1).single()
        repeat(3) { index ->
            val before = Instant.now()
            if (code == "MODERATION_UNAVAILABLE") transactions.fail(row.id, work.attempt, code)
            else transactions.finish(row.id, work.attempt, result(code))
            val saved = rows.findById(row.id).orElseThrow()
            assertEquals(SubmissionStatus.PENDING, saved.status)
            assertNull(saved.errorCode)
            assertNull(saved.decidedAt)
            assertEquals(minOf(index + 1, 2), count(row.id))
            // 같은 실행의 중복 실패·늦은 승인이 다음 회차를 바꾸면 안 된다.
            transactions.fail(row.id, work.attempt, code)
            transactions.finish(row.id, work.attempt, ModerationResult("APPROVED", emptyList(), null))
            assertEquals("원본", stories.findById(row.storyId!!).orElseThrow().title)
            if (index < 2) {
                val scheduled = due(row.id)
                val delay = if (index == 0) 60L else 300L
                assertFalse(scheduled.isBefore(before.plusSeconds(delay)))
                assertTrue(claims.claim(scheduled.minusMillis(1), lease, 1).isEmpty())
                work = claims.claim(scheduled, lease, 1).single()
            } else {
                assertNotNull(jdbc.queryForMap("select held_at from story_submissions where id = ?", row.id)["held_at"])
                assertEquals(code, jdbc.queryForMap("select hold_reason from story_submissions where id = ?", row.id)["hold_reason"])
                assertTrue(claims.claim(Instant.now().plusSeconds(86400), lease, 10).isEmpty())
                assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
                    service.update(stories.findById(row.storyId!!).orElseThrow().publicId.toString(), UpdateStoryRequest(title = "또 수정"), row.userId)
                }.also { assertEquals(409, it.statusCode.value()) }
                val body = service.get(row.publicId.toString(), row.userId)
                assertFalse(body.keys.any { it in setOf("heldAt", "holdReason", "retryCount", "nextAttemptAt") })
                val heldEvents = events.stream(SubmissionHeld::class.java).toList()
                assertEquals(1, heldEvents.size)
                assertEquals(SubmissionHeld(row.publicId, row.kind, code, 3), heldEvents.single())
                assertEquals(0L, events.stream(StoryModerationCompleted::class.java).count())
                service.delete(row.publicId.toString(), row.userId)
                assertFalse(rows.existsById(row.id))
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = ["IMAGE_INVALID", "IMAGE_UNREADABLE", "APPLY_FAILED"])
    fun `사용자 수정과 적용 실패는 즉시 종료한다`(code: String) {
        val row = submit()
        transactions.fail(row.id, row.attempt, code)
        assertEquals(SubmissionStatus.FAILED, rows.findById(row.id).orElseThrow().status)
    }

    @Test fun `내용 위반이나 영구 이미지 오류가 섞이면 재시도하지 않는다`() {
        val row = submit()
        transactions.finish(row.id, row.attempt, result("IMAGE_DOWNLOAD_FAILED").copy(
            issues = listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유"))))
        assertEquals(SubmissionStatus.FAILED, rows.findById(row.id).orElseThrow().status)
        service.delete(row.publicId.toString(), row.userId)
        val second = submit()
        transactions.finish(second.id, second.attempt, result("IMAGE_DOWNLOAD_FAILED").copy(
            imageErrors = listOf(ModerationImageError("thumbnailUrl", "IMAGE_UNREADABLE"))))
        assertEquals(SubmissionStatus.FAILED, rows.findById(second.id).orElseThrow().status)
    }
    @Test fun `재시도 승인과 사용자 재제출은 정상 종료 및 초기화를 따른다`() {
        val row = submit()
        transactions.fail(row.id, row.attempt, "MODERATION_UNAVAILABLE")
        assertEquals(SubmissionStatus.PENDING, rows.findById(row.id).orElseThrow().status)
        val next = claims.claim(due(row.id), lease, 1).single()
        transactions.finish(row.id, next.attempt, ModerationResult("REJECTED", listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
        service.update(stories.findById(row.storyId!!).orElseThrow().publicId.toString(), UpdateStoryRequest(title = "최종 수정"), row.userId)
        assertEquals(0, count(row.id))
        val reset = jdbc.queryForMap("select next_attempt_at, held_at, hold_reason, dispatched_at from story_submissions where id = ?", row.id)
        assertTrue(reset.values.all { it == null })
        val final = claims.claim(Instant.now(), lease, 1).single()
        transactions.finish(row.id, next.attempt, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("원본", stories.findById(row.storyId!!).orElseThrow().title)
        transactions.finish(row.id, final.attempt, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("최종 수정", stories.findById(row.storyId!!).orElseThrow().title)
        assertEquals(SubmissionStatus.APPROVED, rows.findById(row.id).orElseThrow().status)
    }

    @Test fun `보류 알림은 커밋 때만 한번 보내고 롤백에는 보내지 않는다`() {
        val row = submit()
        jdbc.update("update story_submissions set retry_count = 2 where id = ?", row.id)
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        tx.executeWithoutResult { status ->
            transactions.fail(row.id, row.attempt, "MODERATION_UNAVAILABLE")
            org.mockito.Mockito.verifyNoInteractions(notifier)
            status.setRollbackOnly()
        }
        org.mockito.Mockito.verifyNoInteractions(notifier)
        assertNull(rows.findById(row.id).orElseThrow().heldAt)
        transactions.fail(row.id, row.attempt, "MODERATION_UNAVAILABLE")
        transactions.fail(row.id, row.attempt, "MODERATION_UNAVAILABLE")
        org.mockito.Mockito.verify(notifier, org.mockito.Mockito.times(1)).onHeld(SubmissionHeld(row.publicId, row.kind, "MODERATION_UNAVAILABLE", 3))
    }

    @Test fun `재시도 간격은 두 양수만 허용하며 설정값을 사용한다`() {
        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofSeconds(7)), SubmissionRetryPolicy("2s,7s").delays)
        listOf("1m", "1m,5m,10m", "0s,1m", "-1s,1m").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SubmissionRetryPolicy(value) }
        }
    }

}
