package com.knk.manyak.user.event

/**
 * 회원 탈퇴가 진행 중이라는 사실(KNK-2063). 다른 모듈이 그 회원의 데이터를 지우는 신호다.
 *
 * [com.knk.manyak.user.service.UserWithdrawalService]가 사용자 행을 잠그고 익명화한 **그 트랜잭션 안에서** 발행한다.
 * 리스너는 동기 `@EventListener` + `@Transactional(propagation = MANDATORY)`로 같은 트랜잭션에 참여하고, 예외를 전파해
 * 탈퇴 전체를 롤백시킨다. AFTER_COMMIT·비동기로 바꾸면 계정은 DELETED인데 데이터는 일부만 지워진 상태가 남을 수 있다.
 *
 * 순서는 리스너의 `@Order`로 고정한다. story(제출본, 초안)를 먼저, push(푸시 토큰, 알림 내역)를 나중에 지운다.
 * 검수 완료 알림 기록과 탈퇴가 겹칠 때 탈퇴 회원의 알림 내역이 남지 않게 하는 방어는 두 겹이다.
 * 1차: 검수 결과 반영([com.knk.manyak.story.submission.SubmissionTransactions])이 회원, 스토리, 제출본, 인물 순서로 잠가
 * 회원 행에서 탈퇴와 직렬화하고, 탈퇴한 회원이면 기록하지 않는다(MODERATION 5-2-1 행 잠금).
 * 2차: 이 삭제 순서다. NOTIFICATION 4-1-19 "탈퇴와의 순서"가 적어 둔 대로 제출본을 지운 뒤 알림 내역을 지우면, 회원 행 잠금이
 * 없어도 검수가 쥔 제출본 행 잠금에서 탈퇴가 기다렸다가 검수가 쓴 내역까지 지운다. 순서가 뒤집히면 탈퇴가 알림 내역을 먼저
 * 지우고 제출본 잠금을 기다리는 사이에 검수가 내역을 써서 그 내역이 남는다. 회귀 테스트는 `UserWithdrawalIntegrationTests`의
 * 순서 테스트다.
 */
data class UserWithdrawnEvent(val userId: Long)
