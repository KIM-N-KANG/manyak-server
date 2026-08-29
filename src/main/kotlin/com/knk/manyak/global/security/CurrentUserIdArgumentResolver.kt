package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [CurrentUserId] 파라미터를 현재 로그인 사용자의 내부 PK(Long)로 해석한다.
 *
 * - 인증이 [JwtAuthenticationToken]이면 token(Jwt)의 subject를 publicId(UUID)로 파싱해 사용자 내부 id를 반환한다.
 * - 인증이 없거나(익명), JwtAuthenticationToken이 아니거나, subject가 UUID가 아니거나, 그 사용자가 없으면 null을 반환한다.
 *
 * optional 인증 경로에서 익명 호출은 자연히 null이 되고, 유효 토큰만 user_id 귀속으로 이어진다.
 */
@Component
class CurrentUserIdArgumentResolver(
    private val userRepository: UserRepository,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java) &&
            parameter.parameterType == java.lang.Long::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long? {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication !is JwtAuthenticationToken) {
            return null
        }
        val publicId = parsePublicIdOrNull(authentication.token.subject) ?: return null
        val user = userRepository.findByPublicId(publicId) ?: return null
        // 탈퇴(DELETED) 계정의 잔여 access 토큰은 전면 무효다(스펙 §4-3-5, KNK-1019).
        // refresh는 탈퇴 시 폐기되지만 access는 만료까지 최대 30분 살아 있으므로, 해석 계층에서 창을 닫아
        // SuspensionGuard를 거치지 않는 인증 경로(출석 크레딧·초대 redeem·이관 등)까지 한 번에 차단한다.
        if (user.status == UserStatus.DELETED) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
        return user.id
    }

    private fun parsePublicIdOrNull(subject: String?): UUID? {
        // sub가 없는 토큰(subject=null)은 익명으로 본다. UUID.fromString(null)이 NPE를 던지지 않도록 먼저 거른다.
        if (subject == null) return null
        return try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
