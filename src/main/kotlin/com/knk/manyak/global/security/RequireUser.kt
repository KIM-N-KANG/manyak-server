package com.knk.manyak.global.security

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * `@CurrentUserId`로 받은 사용자 id를 요구한다. 인증 필수 경로라도 토큰은 유효하나 사용자 행이 사라진 경우
 * null이 올 수 있어 401로 통일한다.
 */
fun requireUser(userId: Long?): Long =
    userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
