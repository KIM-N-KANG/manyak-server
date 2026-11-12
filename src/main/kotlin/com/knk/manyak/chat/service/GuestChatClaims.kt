package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.GuestChatCandidate
import com.knk.manyak.chat.repository.StoryChatRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 게스트 이관의 채팅 쪽 조회와 클레임(ACCOUNT 5-2-2, CHAT 3-1, KNK-2080). 판정 순서는 이관이 가지고, 이 클래스는 채팅
 * 소유자를 조건부로 바꾸는 명령과 판정 재료만 준다. 이관의 회원 행 잠금 트랜잭션 안에서 부른다.
 */
@Component
class GuestChatClaims(private val chats: StoryChatRepository) {

    /** 삭제되지 않은 채팅만 담는다. */
    fun candidates(publicIds: Set<UUID>): Map<UUID, GuestChatCandidate> =
        chats.findAllByPublicIdInAndDeletedAtIsNull(publicIds).associate {
            it.publicId to GuestChatCandidate(it.userId, it.storyId, it.reachedEndingId, it.reachedEndingNameSnapshot)
        }

    /** 소유자가 비어 있을 때만 [userId]로 바꾸는 조건부 갱신. 바꿨으면 true, 0행이면 false. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claim(publicId: UUID, userId: Long): Boolean = chats.claimByPublicId(publicId, userId) == 1

    /** 조건부 갱신이 0행일 때 실제 소유자를 다시 읽는다. */
    fun ownerOf(publicId: UUID): Long? = chats.findUserIdByPublicId(publicId)
}
