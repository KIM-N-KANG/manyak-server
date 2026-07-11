package com.knk.manyak.credit.repository

/**
 * 대사 대상 후보 그룹(스펙 §4-3-7, KNK-448). 소모 행을 (userId, refType, refId)로 묶은 집계다.
 *
 * 소모·환불이 모두 같은 coarse ref(CHAT→chatPk, STORY→session.id)를 가리켜 행별 1:1 매칭이 불가하므로,
 * 이 그룹 단위로 charge 수를 세어 완료·환불 수와 대조한다.
 *
 * [unitAmount]는 그룹 내 소모 행의 최소 차감액(`MIN(ABS(amount))`)이며, 환불액을 설정값이 아니라 원장의 실제
 * 차감액에서 취한다. 지급량 설정이 바뀌지 않는 한 그룹의 모든 charge는 균일하므로 이 값이 곧 실제 단가여서
 * 정확하다(현실상 거의 모든 경우).
 *
 * 단가가 채팅/세션 수명 도중 바뀌어 charge 금액이 섞이면(드묾), 어느 charge가 stuck인지는 coarse ref와
 * 태깅 없는 in-flight 환불 때문에 행 단위로 식별할 수 없다. 이때는 최소 차감액으로 환불해 **서버가 절대 초과
 * 환불하지 않도록 의도적으로 편향**한다(원장·잔액 무결성 우선). 그 대가로 혼합 단가에서 더 비싼 charge가
 * stuck이면 회원이 소액 미보상될 수 있으나, 이는 (설정 변경 + 혼합 + stuck이 겹치는) 극히 드문 경우의
 * 보수적 선택이다. 행 단위 정확 대사가 필요하면 in-flight 환불이 charge 행을 태깅하도록 소비자 경로를 함께
 * 바꿔야 한다(별도 작업).
 */
data class StuckChargeGroup(
    val userId: Long,
    val refType: String,
    val refId: Long,
    val chargeCount: Long,
    val unitAmount: Long,
)
