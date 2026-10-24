package com.knk.manyak.push.inbox

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.security.requireActiveStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

@Service
class UserNotificationService(
    private val users: UserRepository,
    private val store: UserNotificationStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(userId: Long?, category: NotificationCategory?, limit: Int, cursor: UUID?): NotificationPage {
        val id = activeUser(userId)
        val anchor = cursor?.let { store.cursor(id, it)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "커서 형식이 올바르지 않습니다.") }
        val size = limit.coerceIn(1, 50)
        val rows = store.list(id, category, anchor, size + 1)
        val items = rows.take(size)
        return NotificationPage(items, if (rows.size > size) items.last().notificationId else null)
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Long?): NotificationUnreadCount = store.unreadCount(activeUser(userId))

    @Transactional
    fun read(userId: Long?, notificationId: UUID) {
        val id = activeUser(userId)
        if (store.markRead(id, notificationId, clock.instant()) == 0 && store.cursor(id, notificationId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.")
        }
    }

    @Transactional
    fun readAll(userId: Long?, category: NotificationCategory?) {
        store.markAllRead(activeUser(userId), category, clock.instant())
    }

    // 내역 API는 사용자 행을 잠그지 않는다. 네 요청 모두 상태를 새로 확인한다.
    private fun activeUser(userId: Long?): Long {
        val user = userId?.let { users.findById(it).orElse(null) }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        requireActiveStatus(user.status)
        return user.id
    }
}
