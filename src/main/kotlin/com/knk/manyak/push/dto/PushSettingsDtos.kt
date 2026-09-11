package com.knk.manyak.push.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 알림 수신 동의 상태(KNK-1132). 저장 정본은 광고성 두 값의 **동의 시각**이지만, 화면이 필요한 것은
 * 토글 상태뿐이라 응답에는 boolean만 싣는다 — 동의 시각은 증빙용 내부 값이다.
 */
@Schema(description = "알림 수신 동의 상태(KNK-1132)")
data class PushSettingsResponse(
    @field:Schema(description = "서비스 알림(스토리 완성·검수 완료) 수신 여부. 기본 켜짐(옵트아웃)", example = "true")
    val servicePush: Boolean,

    @field:Schema(description = "광고 알림(프로모션·출석 리마인드) 수신 동의 여부. 기본 꺼짐(옵트인)", example = "false")
    val marketingPush: Boolean,

    @field:Schema(description = "야간(21~08시 KST) 광고 알림 수신 동의 여부. 광고 동의와 별개(옵트인)", example = "false")
    val marketingNightPush: Boolean,
)

/**
 * 알림 수신 동의 변경 요청(KNK-1132). **세 필드 모두 필수**다 — 전체 교체 PUT이라 누락을 기본값으로 채우면
 * 사용자가 보내지 않은 설정이 조용히 꺼진다(컬렉션 전체 교체 PUT의 silent wipe 방지 관례). 누락은 400이다.
 */
@Schema(description = "알림 수신 동의 변경 요청(KNK-1132). 세 필드 모두 필수")
data class PushSettingsUpdateRequest(
    @field:Schema(description = "서비스 알림 수신 여부", example = "true")
    val servicePush: Boolean,

    @field:Schema(description = "광고 알림 수신 동의 여부. true로 바꾸면 동의 시각이 기록된다", example = "true")
    val marketingPush: Boolean,

    @field:Schema(
        description = "야간(21~08시 KST) 광고 알림 수신 동의 여부. marketingPush가 false면 true로 둘 수 없다(400)",
        example = "false",
    )
    val marketingNightPush: Boolean,
)
