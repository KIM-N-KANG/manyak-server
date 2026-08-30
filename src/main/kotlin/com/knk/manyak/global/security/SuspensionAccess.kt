package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * 정지 계정 판정(스펙 §4-5, KNK-499 B20). [status]가 SUSPENDED면 소모·쓰기 요청을 차단한다.
 *
 * 게스트는 이 판정 대상이 아니다(정지는 회원 계정 개념). 관리자 API가 없어(Phase 1 범위 밖) 정지 자체는
 * 운영 DB 직접 수정으로만 이뤄지며, 이 판정은 이미 정지된 상태의 요청을 막는 집행만 담당한다.
 */
fun isActiveAccessAllowed(status: UserStatus): Boolean = status != UserStatus.SUSPENDED

/**
 * 이미 로드한(특히 잠금 잡은) 사용자 행의 상태로 소모·쓰기 자격을 판정한다. 위반이면 즉시 던진다.
 * - SUSPENDED → 403(§4-5 B20), DELETED → 401(계정 무효 — [SuspensionGuard]와 동일 배분).
 *
 * 인증 필터([DeletedAccountRejectionFilter])가 요청 시작 시점에 이미 탈퇴를 거르지만, 필터 통과 직후
 * 탈퇴가 커밋되는 경합에서는 잠금 후 재검사가 마지막 방어선이다(KNK-1019).
 */
fun requireActiveStatus(status: UserStatus) {
    when (status) {
        UserStatus.DELETED -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        UserStatus.SUSPENDED -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
        UserStatus.ACTIVE -> Unit
    }
}

/**
 * userId만 있고 사용자 엔티티를 아직 로드하지 않은 소모·쓰기 엔드포인트에서 쓴다(스펙 §4-5 B20).
 * 이미 사용자 행을 로드한 곳(예: findByIdForUpdate로 조회한 뒤)은 이 컴포넌트 없이
 * [isActiveAccessAllowed]를 직접 호출해 불필요한 추가 조회를 피한다.
 *
 * 정지(SUSPENDED)와 탈퇴(DELETED)를 **다른 코드로** 거부한다.
 * - SUSPENDED → 403: 계정은 살아 있고 쓰기만 금지된 상태다(§4-5 B20).
 * - DELETED → 401: 계정 자체가 무효다. access 토큰이 아직 만료되지 않았을 뿐이므로 사용자 부재와 같은 코드로
 *   통일한다([AccountLinkService.requireLinkableAccount] 선례).
 *
 * 탈퇴 거부가 여기 있는 이유는 [CurrentUserIdArgumentResolver]가 계정 상태를 보지 않고 userId를 해석하기 때문이다.
 * 유효 토큰을 쥔 탈퇴 회원은 이 가드가 없으면 모든 쓰기 경로를 그대로 통과한다.
 */
@Component
class SuspensionGuard(
    private val userRepository: UserRepository,
) {
    fun requireActive(userId: Long?) {
        if (userId == null) return
        // 사용자 행이 없으면 통과시킨다. 게스트 경로와 구분되지 않는 데다 존재 판정은 호출부 몫이다(기존 동작 유지).
        val status = userRepository.findById(userId).orElse(null)?.status ?: return
        requireActiveStatus(status)
    }
}
