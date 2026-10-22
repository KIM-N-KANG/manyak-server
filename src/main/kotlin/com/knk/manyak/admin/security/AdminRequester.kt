package com.knk.manyak.admin.security

import jakarta.servlet.http.HttpServletRequest

/** 요청 본문 대신 판정 필터가 확정한 처리자를 사용한다. */
object AdminRequester {
    internal const val ATTRIBUTE = "manyak.admin.userId"
    fun userId(request: HttpServletRequest): Long =
        requireNotNull(request.getAttribute(ATTRIBUTE) as? Long) { "관리자 판정을 거치지 않은 요청입니다." }
}
