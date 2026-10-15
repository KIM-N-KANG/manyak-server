package com.knk.manyak.story.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.StoryAuthorResponse
import org.springframework.stereotype.Component

/** 공개 목록·검색 카드 작성자를 정본에서 배치 조회한다. 내부 PK는 응답에 노출하지 않는다. */
@Component
class StoryAuthorResolver(private val users: UserRepository) {
    fun resolveAll(userIds: Collection<Long>): Map<Long, StoryAuthorResponse> {
        if (userIds.isEmpty()) return emptyMap()
        return users.findAllById(userIds.distinct())
            .associate { it.id to StoryAuthorResponse(id = null, nickname = it.nickname, profileImageUrl = it.profileImageUrl) }
    }
}
