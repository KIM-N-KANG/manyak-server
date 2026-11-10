package com.knk.manyak.auth.port

/**
 * 계정 모듈이 크레딧 모듈에 요청하는 것을 계정 쪽에서 정의한다(KNK-2064). 구현은 credit 모듈이 하고
 * 계정 모듈은 credit 타입을 직접 참조하지 않는다. 구현은 트랜잭션을 새로 정하지 않고 기존 크레딧 서비스의 경계를 그대로 쓴다.
 */
interface SignupCreditPort {

    /**
     * 게스트 시절 디바이스 체험 사용량을 회원 카운터로 1회 스냅샷한다. 완료하면 true, Redis 장애로 못 하면 false이고
     * 예외를 던지지 않는다. [deviceId]가 없으면 소진 상태로 시드한다.
     */
    fun snapshotTrialAtSignup(userId: Long, deviceId: String?): Boolean

    /** 가입 보상을 [idempotencyKey]로 멱등 적립한다. 지급량과 사유는 크레딧 모듈이 정하고, 실패는 예외로 전파한다. */
    fun rewardSignup(userId: Long, idempotencyKey: String)
}

/** 세션 응답(`GET /auth/me`, `PATCH /users/me`)에 싣는 크레딧 요약. */
interface MeCreditSummaryPort {

    fun balanceOf(userId: Long): Long

    fun hasAttendedToday(userId: Long): Boolean
}
