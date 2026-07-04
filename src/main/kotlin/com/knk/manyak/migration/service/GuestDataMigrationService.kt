package com.knk.manyak.migration.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResponse
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
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
) {

    @Transactional
    fun migrate(userId: Long, request: MigrationRequest): MigrationResponse =
        MigrationResponse(
            stories = claimStories(userId, parseUuids(request.storyIds)),
            chats = claimChats(userId, parseUuids(request.chatIds)),
        )

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
        val ownerByPublicId = storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(ids.toSet())
            .associate { it.publicId to it.userId }
        val claimed = mutableSetOf<UUID>()
        return ids.map { id ->
            val status = status(
                id, userId, ownerByPublicId, claimed,
                claim = { storyChatRepository.claimByPublicId(id, userId) },
                currentOwner = { storyChatRepository.findUserIdByPublicId(id) },
            )
            MigrationResult(id.toString(), status)
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
