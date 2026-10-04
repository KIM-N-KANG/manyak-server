package com.knk.manyak.auth.consent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/** Hibernate는 catch 이전에 DB detail을 기록한다. 새 인증의 DB 오류는 안전한 예외로만 보고한다. */
class SocialAuthPrivacyFilter : TurboFilter() {
    override fun decide(marker: Marker?, logger: Logger?, level: Level?, format: String?, params: Array<out Any>?, t: Throwable?): FilterReply =
        if (active.get() == true && (logger?.name?.startsWith("org.hibernate") == true)) FilterReply.DENY else FilterReply.NEUTRAL

    companion object {
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
