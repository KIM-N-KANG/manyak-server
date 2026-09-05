package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.MeResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.link.AccountLinkService
import com.knk.manyak.credit.service.AttendanceRewardService
import com.knk.manyak.credit.service.CreditWalletService
import org.springframework.stereotype.Component

/**
 * 세션 부트스트랩 응답([MeResponse])을 만든다.
 *
 * `GET /auth/me`와 `PATCH /users/me`(KNK-1147)가 같은 스키마를 돌려주므로 조립을 한 곳에 둔다 —
 * 필드가 늘 때 한쪽만 채워 두 응답이 갈리는 것을 막는다.
 */
@Component
class MeResponseAssembler(
    private val creditWalletService: CreditWalletService,
    private val attendanceRewardService: AttendanceRewardService,
    private val accountLinkService: AccountLinkService,
) {

    fun assemble(user: User): MeResponse = MeResponse(
        id = user.publicId.toString(),
        nickname = user.nickname,
        profileImageUrl = user.profileImageUrl,
        // 첫 페인트용 인라인 썸네일(스펙 §4-3-5 B17). 값은 프리셋 배정(KNK-388) 시 생성되며, 미배정·미생성이면 null.
        profileThumbnailBase64 = user.profileThumbnailBase64,
        status = user.status,
        // 세션 부트스트랩 확장(스펙 §4-3-5 B17): 프론트엔드가 세션 복원 1회 왕복으로 헤더 잔액·출석 UI를 그린다.
        creditBalance = creditWalletService.balanceOf(user.id),
        attendedToday = attendanceRewardService.hasAttendedToday(user.id),
        // 계정 연동 상태(KNK-739). 전용 조회 엔드포인트를 두지 않고 이 응답 하나로 노출한다(왕복을 늘리지 않는다).
        linkedProviders = accountLinkService.linkedProviders(user.id),
    )
}
