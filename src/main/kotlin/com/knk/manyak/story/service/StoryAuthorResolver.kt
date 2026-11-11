package com.knk.manyak.story.service

import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.story.dto.StoryAuthorResponse
import org.springframework.stereotype.Component

/** 공개 목록·검색 카드 작성자를 정본에서 배치 조회한다. 내부 PK는 응답에 노출하지 않는다. */
@Component
class StoryAuthorResolver(private val accounts: AccountQueryService) {
    fun resolveAll(userIds: Collection<Long>): Map<Long, StoryAuthorResponse> {
        return accounts.profilesOf(userIds)
            .mapValues { (_, it) -> StoryAuthorResponse(id = null, nickname = it.nickname, profileImageUrl = it.profileImageUrl) }
    }
}
