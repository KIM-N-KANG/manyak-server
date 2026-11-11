package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.AccountStatusView
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 다른 모듈이 계정 행을 잠그는 진입점(KNK-2056). 잠금은 호출자 트랜잭션 안에서만 의미가 있어 트랜잭션 밖 호출은 거부한다.
 *
 * 잠금 순서는 호출자가 정한다: 사용자 행이 먼저이고 그 뒤에 각 도메인의 행이다(ACCOUNT, STORY, MODERATION, NOTIFICATION 5-2).
 * 잠근 회원은 호출자 트랜잭션의 영속성 컨텍스트에 남으므로, 같은 트랜잭션의 이후 회원 조회는 다시 SQL을 내지 않는다.
 */
@Service
class AccountLockService(private val users: UserRepository) {

    /** 회원 행을 비관적 쓰기 잠금(`SELECT ... FOR UPDATE`, id 기준)으로 잡는다. 회원이 없으면 null이며 판정은 호출자가 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock(userId: Long): AccountStatusView? =
        users.findByIdForUpdate(userId)?.let { AccountStatusView(it.id, it.publicId, it.status) }
}
