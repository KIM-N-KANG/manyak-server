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
 * userId만 있고 사용자 엔티티를 아직 로드하지 않은 소모·쓰기 엔드포인트에서 쓴다(스펙 §4-5 B20).
 * 이미 사용자 행을 로드한 곳(예: findByIdForUpdate로 조회한 뒤)은 이 컴포넌트 없이
 * [isActiveAccessAllowed]를 직접 호출해 불필요한 추가 조회를 피한다.
 */
@Component
class SuspensionGuard(
    private val userRepository: UserRepository,
) {
    fun requireActive(userId: Long?) {
        if (userId == null) return
        val status = userRepository.findById(userId).orElse(null)?.status ?: return
        if (!isActiveAccessAllowed(status)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다.")
        }
    }
}
