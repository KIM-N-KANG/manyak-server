package com.knk.manyak.global.observability.analytics

import java.util.UUID

/**
 * 분석 이벤트의 `user_id`(회원 public_id)를 계정 모듈에 묻는 입구(KNK-2068). global이 정의하고 auth가 구현한다
 * (BE-061: 아래 계층이 값을 돌려받아야 하면 아래 계층이 인터페이스를 정의한다).
 */
fun interface UserPublicIdPort {

    /** 내부 회원 id의 공개 식별자. 회원이 없으면 null이다. 조회 실패는 예외로 던지고 [ServerAnalytics]가 삼킨다. */
    fun publicIdOf(userId: Long): UUID?
}
