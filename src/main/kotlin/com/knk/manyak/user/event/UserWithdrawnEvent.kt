package com.knk.manyak.user.event

/**
 * 회원 탈퇴가 진행 중이라는 사실(KNK-2063). 다른 모듈이 그 회원의 데이터를 지우는 신호다.
 *
 * [com.knk.manyak.user.service.UserWithdrawalService]가 사용자 행을 잠그고 익명화한 **그 트랜잭션 안에서** 발행한다.
 * 리스너는 동기 `@EventListener` + `@Transactional(propagation = MANDATORY)`로 같은 트랜잭션에 참여하고, 예외를 전파해
 * 탈퇴 전체를 롤백시킨다. AFTER_COMMIT·비동기로 바꾸면 계정은 DELETED인데 데이터는 일부만 지워진 상태가 남을 수 있다.
 *
 * 순서는 리스너의 `@Order`로 고정한다: story(제출본 → 초안) 다음 push(푸시 토큰 → 알림 내역).
 */
data class UserWithdrawnEvent(val userId: Long)
