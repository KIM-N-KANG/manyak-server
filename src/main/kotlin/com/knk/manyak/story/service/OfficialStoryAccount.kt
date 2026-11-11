package com.knk.manyak.story.service

import com.knk.manyak.auth.service.AccountQueryService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 오리지널 필터와 모든 스토리 카드가 공유하는 공식 계정 조회. 찾은 id는 프로세스 동안 기억한다(회원 id는 바뀌지 않는다).
 * 못 찾은 경우는 기억하지 않아, 공식 계정이 나중에 생겨도 다음 호출이 찾는다.
 */
@Component
class OfficialStoryAccount(
    private val accounts: AccountQueryService,
    @Value("\${manyak.official-user-public-id:}") officialUserPublicId: String,
) {
    // 잘못된 UUID는 기존 목록과 같이 기동 시점에 실패시킨다.
    private val officialUserPublicId: UUID? =
        officialUserPublicId.takeIf { it.isNotBlank() }?.let(UUID::fromString)

    @Volatile private var rememberedId: Long? = null

    /** 설정이 비었거나 해당 publicId의 회원이 없으면 null이다. */
    fun officialUserId(): Long? =
        rememberedId ?: officialUserPublicId?.let(accounts::userIdOf)?.also { rememberedId = it }

    /** 기억한 id를 지운다. 테스트가 DB를 비워 회원 id가 다시 매겨질 때 부른다. 운영에서는 부를 일이 없다. */
    fun forget() {
        rememberedId = null
    }
}
