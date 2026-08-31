package com.knk.manyak.credit.repository

/**
 * 대사 대상 후보 그룹(스펙 §4-3-7, KNK-448). 소모 행을 (userId, refType, refId)로 묶은 집계다.
 *
 * 소모·환불이 모두 같은 coarse ref(CHAT→chatPk, STORY→session.id)를 가리켜 행별 1:1 매칭이 불가하므로,
 * 이 그룹 단위로 charge 수를 세어 완료·환불 수와 대조한다.
 *
 * [unitAmount]는 그룹 내 소모 행의 최소 차감액(`MIN(ABS(amount))`)이며, 환불액을 설정값이 아니라 원장의 실제
 * 차감액에서 취한다. 그룹의 모든 charge가 균일하면 이 값이 곧 실제 단가여서 정확하다.
 *
 * **전제 정정(KNK-1056)**: 예전 이 주석은 "지급량 설정이 바뀌지 않는 한 균일하므로 정확하다"고 적었으나,
 * 수치가 런타임에 바뀌는 정책값이 되면서(`credit_policies` 오버라이드) 그 전제가 사라졌다. 혼합 단가는 이제
 * 드문 예외가 아니라 **정책 변경마다 가능한 상태**다 — 변경 시점을 걸치는 채팅/세션의 charge 금액이 섞인다.
 *
 * 단가가 섞이면 어느 charge가 stuck인지는 coarse ref와 태깅 없는 in-flight 환불 때문에 행 단위로 식별할 수
 * 없다. 이때는 최소 차감액으로 환불해 **서버가 절대 초과 환불하지 않도록 의도적으로 편향**한다(원장·잔액
 * 무결성 우선). 잔여 리스크는 회원의 소액 미보상 — 더 비싼 charge가 stuck이면 **단가 차액만큼 덜 돌려준다**.
 * 발생하려면 (정책 변경) × (프로세스 중단 등으로 stuck) × (같은 chat/session이 변경 시점을 걸침)이 겹쳐야 하고,
 * 편향 방향이 서버에 안전한 쪽이라 이 트레이드오프를 유지한다.
 *
 * 대신 조용한 미보상을 **탐지 가능한 미보상**으로 바꾼다: [maxUnitAmount]를 함께 집계해 [unitAmount]와 다르면
 * 대사 시 구조화 warn 로그를 남긴다([com.knk.manyak.credit.service.CreditReconciliationService]).
 *
 * 행 단위 정확 대사가 필요하면 in-flight 환불이 charge 행을 태깅하도록 소비자 경로 세 곳을 함께 바꿔야 한다
 * (별도 티켓 규모).
 */
data class StuckChargeGroup(
    val userId: Long,
    val refType: String,
    val refId: Long,
    val chargeCount: Long,
    val unitAmount: Long,
    // 그룹 내 최대 차감액. 환불 계산에는 쓰지 않고(MIN 유지), 혼합 단가 탐지에만 쓴다.
    val maxUnitAmount: Long,
)
