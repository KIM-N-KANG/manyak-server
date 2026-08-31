package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 크레딧 수치의 런타임 오버라이드 1건(KNK-1056, 스펙 §4-3-7).
 *
 * 행이 없으면 yml 기본값을 쓴다. 이 테이블은 "기본값과 다르게 갈 값"만 담는 얇은 오버라이드 층이다.
 *
 * [policyKey]를 enum이 아니라 String으로 든다: 운영 SQL이 오타 키를 넣어도 그 행만 무시되면 되는데,
 * @Enumerated로 매핑하면 조회 자체가 터져 크레딧 경로 전체가 죽는다. 코드 쪽 오타는
 * [com.knk.manyak.credit.service.CreditPolicyKey] enum이 막는다.
 */
@Entity
@Table(name = "credit_policies")
class CreditPolicy(
    @Id
    @Column(name = "policy_key", nullable = false)
    val policyKey: String = "",

    @Column(nullable = false)
    val amount: Long = 0,

    // NULL이면 상시 적용. 값이 있으면 그 시각을 지나는 순간 기본값으로 되돌아간다(이벤트 종료 장치).
    @Column(name = "effective_until")
    val effectiveUntil: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    fun isEffectiveAt(now: Instant): Boolean = effectiveUntil?.isAfter(now) ?: true
}
