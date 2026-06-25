package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Story
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryRepository : JpaRepository<Story, Long> {
    // 소프트 삭제된 스토리(deleted_at IS NOT NULL)는 조회·삭제 대상에서 제외한다.
    fun findByIdAndDeletedAtIsNull(id: Long): Story?

    fun findAllByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<Story>

    // KNK-256: API 외부 식별자(public_id) 기준 조회. 순차 PK 열거(IDOR) 차단.
    // 삭제된 스토리 제외라 KNK-257(삭제 스토리 채팅 생성 차단)도 이 조회로 함께 해결된다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): Story?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<Story>
}
