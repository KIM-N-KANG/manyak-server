package com.knk.manyak.auth.consent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Hibernate는 catch 이전에 DB detail을 기록한다. 인증과 프로필의 DB 오류는 안전한 예외로만 보고한다. */
class SocialAuthPrivacyFilter : TurboFilter() {
    override fun decide(marker: Marker?, logger: Logger?, level: Level?, format: String?, params: Array<out Any>?, t: Throwable?): FilterReply =
        if (active.get() == true && (logger?.name?.startsWith("org.hibernate") == true)) FilterReply.DENY else FilterReply.NEUTRAL

    companion object {
        /** DB 원문 예외를 전역 로그·Sentry로 전달하지 않는다. 트랜잭션의 롤백 뒤 호출한다. */
        fun <T> safely(block: () -> T): T = protecting {
            try { block() } catch (_: DataAccessException) {
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.",
                )
            }
        }

        private val active = ThreadLocal<Boolean>()
        fun <T> protecting(block: () -> T): T {
            val previous = active.get()
            active.set(true)
            try { return block() } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
    }
}
