package com.knk.manyak.migration.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResponse
import com.knk.manyak.migration.dto.MigrationResult
import com.knk.manyak.migration.dto.MigrationStatus
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
 */
@Service
class GuestDataMigrationService(
    private val storyRepository: StoryRepository,
    private val storyChatRepository: StoryChatRepository,
) {

    @Transactional
    fun migrate(userId: Long, request: MigrationRequest): MigrationResponse =
        MigrationResponse(
            stories = claimStories(userId, parseUuids(request.storyIds)),
            chats = claimChats(userId, parseUuids(request.chatIds)),
        )

    private fun claimStories(userId: Long, ids: List<UUID>): List<MigrationResult> {
        val byPublicId = storyRepository.findAllByPublicIdInAndDeletedAtIsNull(ids.toSet())
            .associateBy { it.publicId }
        return ids.map { id ->
            val story = byPublicId[id]
            val status = when {
                story == null -> MigrationStatus.NOT_FOUND
                story.userId == null -> { story.userId = userId; MigrationStatus.MIGRATED }
                story.userId == userId -> MigrationStatus.ALREADY_OWNED
                else -> MigrationStatus.CONFLICT
            }
            MigrationResult(id.toString(), status)
        }
    }

    private fun claimChats(userId: Long, ids: List<UUID>): List<MigrationResult> {
        val byPublicId = storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(ids.toSet())
            .associateBy { it.publicId }
        return ids.map { id ->
            val chat = byPublicId[id]
            val status = when {
                chat == null -> MigrationStatus.NOT_FOUND
                chat.userId == null -> { chat.userId = userId; MigrationStatus.MIGRATED }
                chat.userId == userId -> MigrationStatus.ALREADY_OWNED
                else -> MigrationStatus.CONFLICT
            }
            MigrationResult(id.toString(), status)
        }
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
