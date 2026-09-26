package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcTaskDecorator
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.GeneralStoryCreationService
import com.knk.manyak.story.service.StoryEditService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@Configuration
class ModerationExecutionConfig {
    @Bean("storyModerationExecutor")
    fun executor(@Value("\${manyak.ai.moderation.pool-size:4}") poolSize: Int): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = poolSize
        maxPoolSize = poolSize
        queueCapacity = 100
        setThreadNamePrefix("story-moderation-")
        setTaskDecorator(MdcTaskDecorator())
        initialize()
    }
}

/** AI 왕복은 트랜잭션 밖. DB 실패는 PENDING을 남겨 회수 대상으로 둔다. */
@Component
class SubmissionExecutor(
    @Qualifier("storyModerationExecutor") private val executor: Executor,
    private val transactions: SubmissionTransactions,
    private val client: StoryModerationClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun submitted(event: SubmissionRequested) {
        try { executor.execute { run(event) } }
        catch (_: RejectedExecutionException) { log.warn("moderation_dispatch_rejected submission={} attempt={}", event.id, event.attempt) }
    }
    fun run(event: SubmissionRequested) {
        try {
            val input = transactions.start(event.id, event.attempt) ?: return
            val result = try { client.moderate(input).validated(input) } catch (_: Exception) {
                transactions.fail(event.id, event.attempt, "MODERATION_UNAVAILABLE")
                return
            }
            try { transactions.finish(event.id, event.attempt, result) }
            catch (_: ResponseStatusException) { transactions.fail(event.id, event.attempt, "APPLY_FAILED") }
            catch (_: IllegalArgumentException) { transactions.fail(event.id, event.attempt, "APPLY_FAILED") }
        } catch (ex: Exception) {
            // 입력·URL·예외 메시지는 기록하지 않는다. DB 장애라면 커밋이 롤백되어 회수 가능하다.
            log.warn("moderation_execution_deferred submission={} attempt={} error={}", event.id, event.attempt, ex.javaClass.simpleName)
        }
    }
}

@Service
class SubmissionTransactions(
    private val submissions: StorySubmissionRepository,
    private val stories: StoryRepository,
    private val users: UserRepository,
    private val creation: GeneralStoryCreationService,
    private val edit: StoryEditService,
    private val forms: SubmissionFormAssembler,
    private val mapper: ObjectMapper,
    private val events: ApplicationEventPublisher,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun start(id: Long, attempt: Int): JsonNode? {
        val row = pending(id, attempt) ?: return null
        row.dispatchedAt = Instant.now()
        row.updatedAt = row.dispatchedAt
        return forms.aiInput(mapper.readTree(row.inputForm))
    }

    @Transactional
    fun finish(id: Long, attempt: Int, result: ModerationResult) {
        val row = pending(id, attempt) ?: return
        if (result.errorCode != null) {
            decide(row, SubmissionStatus.FAILED, emptyList(), result.errorCode)
        } else if (result.decision == "REJECTED") {
            decide(row, SubmissionStatus.REJECTED, result.issues, null)
        } else {
            if (row.kind == SubmissionKind.CREATE) {
                val created = creation.createGeneralStory(mapper.readValue(row.payload, CreateGeneralStoryRequest::class.java), row.userId)
                row.storyId = stories.findByPublicIdAndDeletedAtIsNull(java.util.UUID.fromString(created.id))!!.id
            } else {
                val story = stories.findById(requireNotNull(row.storyId)).orElseThrow()
                val request = mapper.readValue(row.payload, UpdateStoryRequest::class.java)
                // 반려 이후 개별 삭제된 이미지는 검증·폼에서 제외했으므로 적용도 같은 집합을 사용한다.
                val approvedForm = mapper.readTree(row.inputForm)
                val effective = request.copy(characters = request.characters?.mapIndexed { index, character ->
                    val kept = approvedForm.path("characters").path(index).path("images").mapNotNull { it.path("id").takeUnless { id -> id.isNull || id.isMissingNode }?.asText() }.toSet()
                    character.copy(images = character.images?.filter { it.id == null || it.id in kept })
                })
                edit.updateStory(story.publicId.toString(), row.userId, effective)
            }
            entityManager.flush()
            decide(row, SubmissionStatus.APPROVED, emptyList(), null)
        }
    }

    @Transactional
    fun fail(id: Long, attempt: Int, code: String) {
        val row = pending(id, attempt) ?: return
        decide(row, SubmissionStatus.FAILED, emptyList(), code)
    }

    @Transactional
    fun reclaim(id: Long, cutoff: Instant) {
        val row = submissions.lockById(id) ?: return
        entityManager.refresh(row)
        if (row.status != SubmissionStatus.PENDING || row.dispatchedAt > cutoff) return
        row.resubmit(row.payload)
        events.publishEvent(SubmissionRequested(row.id, row.attempt))
    }

    private fun pending(id: Long, attempt: Int): StorySubmission? {
        val candidate = submissions.findById(id).orElse(null) ?: return null
        val userId = candidate.userId
        val storyId = candidate.storyId
        entityManager.detach(candidate)
        // 회원 탈퇴와 직렬화한 뒤 스토리 → 제출본 → 인물 순으로 잠근다.
        val user = users.findByIdForUpdate(userId) ?: return null
        if (user.status == UserStatus.DELETED) return null
        if (storyId != null) {
            val story = stories.findById(storyId).orElse(null) ?: return null
            entityManager.detach(story)
            if (stories.findByPublicIdAndDeletedAtIsNullForUpdate(story.publicId) == null) return null
        }
        return submissions.lockById(id)?.takeIf { it.status == SubmissionStatus.PENDING && it.attempt == attempt }
    }

    private fun decide(row: StorySubmission, status: SubmissionStatus, issues: List<ModerationIssue>, code: String?) {
        row.status = status
        row.issues = issues
        row.errorCode = code
        row.decidedAt = Instant.now()
        row.updatedAt = row.decidedAt!!
        events.publishEvent(StoryModerationCompleted(row.userId, row.publicId.toString(), row.storyId?.let { stories.findById(it).orElseThrow().publicId.toString() }, status, row.attempt))
    }
}

@Component
class SubmissionReclaimScheduler(
    private val submissions: StorySubmissionRepository,
    private val transactions: SubmissionTransactions,
    @Value("\${manyak.ai.moderation.reclaim-after:300s}") private val reclaimAfter: Duration,
) {
    @Scheduled(fixedDelayString = "\${manyak.ai.moderation.reclaim-interval:60000}")
    fun reclaim() {
        val cutoff = Instant.now().minus(reclaimAfter)
        submissions.findByStatusAndDispatchedAtBefore(SubmissionStatus.PENDING, cutoff, PageRequest.of(0, 100))
            .forEach { transactions.reclaim(it.id, cutoff) }
    }
}
