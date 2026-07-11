package com.knk.manyak.migration.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.global.security.isActiveAccessAllowed
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResponse
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
import com.knk.manyak.story.entity.UserStoryEndingReach
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.UserStoryEndingReachRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * 게스트(비로그인)가 기기에 쌓은 스토리·채팅의 소유권을 로그인 계정으로 이관(클레임)한다.
 *
 * 서버에는 게스트 식별 수단이 없어(콘텐츠 행에 device_id를 저장하지 않음) 클라이언트가 제출한 공개 ID 목록을 신뢰한다.
 * `user_id`가 NULL인 행에만 요청자의 `user_id`를 설정하고, 스토리·채팅은 서로 독립 처리한다(채팅은 참조 스토리 소유자와 무관).
 * 효과는 멱등하며(재호출해도 소유권 불변, 응답 status만 상태를 반영), 항목별 부분 성공이라 일부 충돌이 전체를 롤백하지 않는다.
 *
 * 클레임은 조건부 UPDATE(`WHERE user_id IS NULL`)로 원자적으로 수행한다. 두 계정이 같은 게스트 ID를 동시에 제출해도
 * DB가 갱신 시점에 조건을 재평가하므로 한 트랜잭션만 MIGRATED가 되고 나머지는 CONFLICT가 된다(소유권 무결성 보장).
 *
 * 스토리를 새로 클레임하면 연결된 생성 세션(`story_creation_sessions`)의 소유권도 함께 클레임한다.
 * 세션 소유자가 NULL로 남으면 storyline 평가/취소가 익명 취급으로 열리므로([StorylineRatingService]) 스토리와 일치시킨다.
 */
@Service
class GuestDataMigrationService(
    private val storyRepository: StoryRepository,
    private val storyChatRepository: StoryChatRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val userRepository: UserRepository,
    private val userStoryEndingReachRepository: UserStoryEndingReachRepository,
    private val serverAnalytics: ServerAnalytics,
) {

    private companion object {
        // 이관 시도(성공 0건 포함) 계정당 상한(스펙 §4-3-5 B19, 2026-07-08 결정: 5).
        const val MAX_MIGRATION_ATTEMPTS = 5
    }

    @Transactional
    fun migrate(userId: Long, request: MigrationRequest): MigrationResponse {
        // 이관 잠금 게이트(스펙 §4-3-5, KNK-480): 이관은 계정당 1회만 허용한다.
        // 사용자 행에 비관적 쓰기 락(findByIdForUpdate)을 걸어 같은 계정의 동시 최초 이관을 직렬화한다.
        // 락이 없으면 두 요청이 서로 다른 게스트 ID로 동시에 들어와 둘 다 migrated_at==null을 읽고 통과해
        // 1회 상한을 우회할 수 있다(Codex P1). migrated_at이 이미 있으면 어떤 항목도 평가하지 않고 닫힌 응답을 돌려준다.
        // userId는 컨트롤러에서 인증된 실 사용자 id이므로 조회는 항상 성공한다(방어적으로 없으면 401).
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 사용자 행을 이미 로드했으므로 추가 조회 없이 판정한다.
        if (!isActiveAccessAllowed(user.status)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
        }
        if (user.migratedAt != null) {
            return MigrationResponse(stories = emptyList(), chats = emptyList(), migrationClosed = true)
        }
        // 이관 시도 상한(스펙 §4-3-5 B19, KNK-500): 성공 0건 호출도 포함해 계정당 5회까지만 평가한다.
        // 상한을 넘으면 닫힌 계정과 동일하게 처리해(평가·클레임 없이 200) 소유 상태 열거 오라클을 제한한다.
        // 이미 사용자 행을 락으로 잡은 뒤 판정하므로 동시 호출도 상한을 우회하지 못한다.
        if (user.migrationAttempts >= MAX_MIGRATION_ATTEMPTS) {
            return MigrationResponse(stories = emptyList(), chats = emptyList(), migrationClosed = true)
        }
        user.migrationAttempts += 1

        val (stories, chats) = try {
            claimStories(userId, parseUuids(request.storyIds)) to claimChats(userId, parseUuids(request.chatIds))
        } catch (e: ResponseStatusException) {
            // 요청 UUID 형식 오류(400)는 요청 자체 실패로 분류한다(스펙 §6-4-2-8 마이그레이션 실패 = validation).
            if (e.statusCode == HttpStatus.BAD_REQUEST) serverAnalytics.migrationFailed(userId, AnalyticsErrorType.VALIDATION)
            else serverAnalytics.migrationFailed(userId, AnalyticsErrorType.SERVER)
            throw e
        } catch (e: Exception) {
            serverAnalytics.migrationFailed(userId, AnalyticsErrorType.SERVER)
            throw e
        }

        // 한 건이라도 이번 요청으로 소유권을 얻었으면 계정을 잠근다(관리 엔티티 변경 → 트랜잭션 커밋 시 UPDATE).
        // 잠금은 기기를 갈아타며 반복 이관하는 어뷰징을 막는다(순차 재호출은 migrated_at으로, 동시 요청은 위 행 락으로 직렬화).
        if (stories.any { it.status == MigrationStatus.MIGRATED } ||
            chats.any { it.status == MigrationStatus.MIGRATED }
        ) {
            user.migratedAt = Instant.now()
        }
        publishMigrationSucceeded(userId, stories, chats)
        return MigrationResponse(stories = stories, chats = chats)
    }

    /**
     * 마이그레이션 처리 완료 이벤트를 발행한다(스펙 §6-4-2-8, 부분 성공 포함). 제출 배열이 스토리·채팅 모두 비어
     * 평가 항목이 없으면 0건 노이즈 방지를 위해 발행하지 않는다. 카운트 합은 제출 총수와 일치한다(정합 검증용).
     */
    private fun publishMigrationSucceeded(userId: Long, stories: List<MigrationResult>, chats: List<MigrationResult>) {
        if (stories.isEmpty() && chats.isEmpty()) return
        val all = stories + chats
        val migratedStoryCount = stories.count { it.status == MigrationStatus.MIGRATED }
        val migratedChatCount = chats.count { it.status == MigrationStatus.MIGRATED }
        val alreadyOwnedCount = all.count { it.status == MigrationStatus.ALREADY_OWNED }
        val conflictCount = all.count { it.status == MigrationStatus.CONFLICT }
        val notFoundCount = all.count { it.status == MigrationStatus.NOT_FOUND }
        // 성공 이벤트는 커밋 후에만 발행한다(Codex P2): 이 메서드는 @Transactional migrate 안에서 호출되므로,
        // 커밋 단계 실패로 롤백되면 클레임이 반영되지 않는데 성공 이벤트만 남는 false success가 생긴다. 롤백 시 afterCommit은 실행되지 않는다.
        val emit = {
            serverAnalytics.migrationSucceeded(
                userId = userId,
                migratedStoryCount = migratedStoryCount,
                migratedChatCount = migratedChatCount,
                alreadyOwnedCount = alreadyOwnedCount,
                conflictCount = conflictCount,
                notFoundCount = notFoundCount,
            )
        }
        // 트랜잭션 동기화가 활성일 때만 커밋 후로 미룬다. 트랜잭션 밖 호출(단위 테스트 등)에서는 곧바로 발행한다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = emit()
            })
        } else {
            emit()
        }
    }

    private fun claimStories(userId: Long, ids: List<UUID>): List<MigrationResult> {
        val storyByPublicId = storyRepository.findAllByPublicIdInAndDeletedAtIsNull(ids.toSet())
            .associateBy { it.publicId }
        val ownerByPublicId = storyByPublicId.mapValues { it.value.userId }
        val claimed = mutableSetOf<UUID>()
        return ids.map { id ->
            val status = status(
                id, userId, ownerByPublicId, claimed,
                claim = { storyRepository.claimByPublicId(id, userId) },
                currentOwner = { storyRepository.findUserIdByPublicId(id) },
            )
            if (status == MigrationStatus.MIGRATED) {
                // 스토리를 새로 클레임했으면 연결된 생성 세션 소유권도 맞춘다(익명 세션의 storyline 평가가 열리는 것 방지).
                storyCreationSessionRepository.claimByStoryId(storyByPublicId.getValue(id).id, userId)
            }
            MigrationResult(id.toString(), status)
        }
    }

    private fun claimChats(userId: Long, ids: List<UUID>): List<MigrationResult> {
        val chatByPublicId = storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(ids.toSet())
            .associateBy { it.publicId }
        val ownerByPublicId = chatByPublicId.mapValues { it.value.userId }
        val claimed = mutableSetOf<UUID>()
        return ids.map { id ->
            val status = status(
                id, userId, ownerByPublicId, claimed,
                claim = { storyChatRepository.claimByPublicId(id, userId) },
                currentOwner = { storyChatRepository.findUserIdByPublicId(id) },
            )
            // 새로 클레임한 게스트 채팅이 엔딩에 도달했었다면 회원 도달 집계에 백필한다(스펙 §4-3-10 이관 백필).
            if (status == MigrationStatus.MIGRATED) {
                backfillEndingReach(userId, chatByPublicId.getValue(id))
            }
            MigrationResult(id.toString(), status)
        }
    }

    /** 이관된 채팅이 도달한 엔딩을 회원 도달 집계에 최초 1회 upsert한다. 도달 전이면 아무것도 하지 않는다. */
    private fun backfillEndingReach(userId: Long, chat: StoryChat) {
        val endingId = chat.reachedEndingId ?: return
        if (!userStoryEndingReachRepository.existsByUserIdAndStoryIdAndEndingId(userId, chat.storyId, endingId)) {
            userStoryEndingReachRepository.save(
                UserStoryEndingReach(userId = userId, storyId = chat.storyId, endingId = endingId),
            )
        }
    }

    /**
     * 스냅샷 소유권으로 우선 판정하고, 게스트(NULL) 행만 [claim] 조건부 UPDATE로 원자적으로 클레임한다.
     * - 스냅샷에 없음 → NOT_FOUND, 요청자 소유 → ALREADY_OWNED, 타인 소유 → CONFLICT.
     * - 게스트 행: 같은 요청 내 중복이면 ALREADY_OWNED, claim이 1이면 MIGRATED.
     * - claim이 0(동시 요청에 선점됨)이면 [currentOwner]로 실제 소유자를 재확인한다: 요청자면 ALREADY_OWNED(멱등), 아니면 CONFLICT.
     */
    private fun status(
        id: UUID,
        userId: Long,
        ownerByPublicId: Map<UUID, Long?>,
        claimed: MutableSet<UUID>,
        claim: () -> Int,
        currentOwner: () -> Long?,
    ): MigrationStatus = when {
        id !in ownerByPublicId -> MigrationStatus.NOT_FOUND
        ownerByPublicId[id] == userId -> MigrationStatus.ALREADY_OWNED
        ownerByPublicId[id] != null -> MigrationStatus.CONFLICT
        id in claimed -> MigrationStatus.ALREADY_OWNED
        claim() == 1 -> { claimed.add(id); MigrationStatus.MIGRATED }
        currentOwner() == userId -> MigrationStatus.ALREADY_OWNED
        else -> MigrationStatus.CONFLICT
    }

    /**
     * 제출된 문자열을 UUID로 파싱한다. 형식 오류는 요청 전체를 400으로 본다.
     * 부분 성공(항목별 status)은 소유권 판정에만 적용하고, 잘못된 입력 형식은 요청 자체를 거부한다.
     */
    private fun parseUuids(rawIds: List<String>): List<UUID> =
        rawIds.map { raw ->
            try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "공개 ID 형식이 올바르지 않습니다: $raw")
            }
        }
}
