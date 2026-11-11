package com.knk.manyak.global.security

import com.knk.manyak.global.util.parsePublicIdOrNull
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException

/**
 * [CurrentUserId] 파라미터를 현재 로그인 사용자의 내부 PK(Long)로 해석한다.
 *
 * - 인증이 [JwtAuthenticationToken]이면 token(Jwt)의 subject를 publicId(UUID)로 파싱해 사용자 내부 id를 반환한다.
 * - 인증이 없거나(익명), JwtAuthenticationToken이 아니거나, subject가 UUID가 아니거나, 그 사용자가 없으면 null을 반환한다.
 *
 * optional 인증 경로에서 익명 호출은 자연히 null이 되고, 유효 토큰만 user_id 귀속으로 이어진다.
 *
 * 앱 체인의 [DeletedAccountRejectionFilter]가 같은 토큰 주체를 판정하며 회원 PK를 [RESOLVED_USER_ID_ATTRIBUTE]에 남기면
 * 그 값을 쓰고 다시 조회하지 않는다(KNK-2068). 회원의 id와 public_id는 바뀌지 않고 회원 행은 지워지지 않으므로(탈퇴는 상태 변경)
 * 다시 조회해도 같은 값이다. 속성이 없으면(필터를 타지 않은 경로, 회원 없음) 전과 같이 포트로 조회한다.
 */
@Component
class CurrentUserIdArgumentResolver(
    private val accountAccess: AccountAccessPort,
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
        (webRequest.getAttribute(RESOLVED_USER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? Long)?.let { return it }
        val publicId = parsePublicIdOrNull(authentication.token.subject) ?: return null
        // 탈퇴(DELETED) 토큰은 여기 오기 전에 DeletedAccountRejectionFilter가 401로 끊는다(KNK-1019).
        return accountAccess.userIdOf(publicId)
    }

    companion object {
        /** 탈퇴 필터가 판정한 회원 PK를 담는 요청 속성 이름. 서버 코드만 쓴다. */
        val RESOLVED_USER_ID_ATTRIBUTE: String = CurrentUserIdArgumentResolver::class.java.name + ".RESOLVED_USER_ID"
    }
}
