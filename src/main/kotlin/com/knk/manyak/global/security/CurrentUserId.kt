package com.knk.manyak.global.security

/**
 * 컨트롤러 핸들러 파라미터에 현재 로그인 사용자의 내부 PK(Long)를 주입한다.
 *
 * optional 인증 경로에서 쓴다. 유효 access 토큰이 있으면 그 사용자의 내부 id가, 토큰이 없거나
 * 무효·만료면 null이 주입된다. 파라미터 타입은 nullable [Long]이어야 한다(익명 허용).
 *
 * @see CurrentUserIdArgumentResolver
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
