package com.knk.manyak.credit.repository

/**
 * 대사 대상 후보 그룹(스펙 §4-3-7, KNK-448). 소모 행을 (userId, refType, refId)로 묶은 집계다.
 *
 * 소모·환불이 모두 같은 coarse ref(CHAT→chatPk, STORY→session.id)를 가리켜 행별 1:1 매칭이 불가하므로,
 * 이 그룹 단위로 charge 수를 세어 완료·환불 수와 대조한다.
 *
 * [unitAmount]는 그룹 내 소모 행의 최소 차감액(정상적으로는 균일)이다. 환불액을 설정값이 아니라 원장의 실제
 * 차감액에서 취해, 지급량 설정이 바뀌어도 과거 차감과 정확히 대응시키고 초과 환불을 피한다.
 */
data class StuckChargeGroup(
    val userId: Long,
    val refType: String,
    val refId: Long,
    val chargeCount: Long,
    val unitAmount: Long,
)
