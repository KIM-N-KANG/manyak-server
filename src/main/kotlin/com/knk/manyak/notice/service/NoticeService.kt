package com.knk.manyak.notice.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.notice.dto.*
import com.knk.manyak.notice.entity.*
import com.knk.manyak.notice.repository.*
import com.knk.manyak.push.service.NoticePushService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class NoticeService(
    private val notices: NoticeRepository,
    private val reads: NoticeReadRepository,
    private val cursors: NoticeCursorCodec,
    private val pushes: NoticePushService,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
    private val logger: StructuredLogger,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(request: AdminNoticesRequest) = reads.list(request)
    fun detail(id: UUID) = reads.detail(id) ?: missing()
    fun published(limit: Int, cursor: String?): PublicNoticePageResponse {
        val size = limit.coerceIn(1, 50)
        val rows = reads.published(size + 1, cursor?.let(cursors::decode))
        val items = rows.take(size)
        return PublicNoticePageResponse(items.map { it.item }, if (rows.size > size) cursors.encode(items.last().cursor) else null)
    }
    @Transactional(readOnly = true)
    fun publishedDetail(id: String): PublicNoticeResponse {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()?.takeIf { it.toString().equals(id, true) } ?: missing()
        val notice = notices.findByPublicId(uuid)?.takeIf { it.status == NoticeStatus.PUBLISHED } ?: missing()
        return PublicNoticeResponse(notice.publicId, notice.title, notice.body, notice.publishedAt!!, notice.updatedAt)
    }
    @Transactional
    fun create(actor: Long, request: CreateNoticeRequest): AdminNoticeResponse {
        val now = now()
        val row = notices.save(Notice(title = request.title, body = request.body, createdByUserId = actor, createdAt = now, updatedAt = now))
        val result = detail(row.publicId)
        audits.recordChange(actor, AdminAuditAction.NOTICE_CREATED, row.publicId.toString(), request.reason, after = state(row))
        return result
    }
    @Transactional
    fun update(actor: Long, id: UUID, request: UpdateNoticeRequest): AdminNoticeResponse {
        if (request.title == null && request.body == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제목 또는 본문을 지정하세요.")
        val row = lock(id); val before = state(row)
        request.title?.let { row.title = it }; request.body?.let { row.body = it }; row.updatedAt = now()
        notices.flush()
        val result = detail(id)
        audits.recordChange(actor, AdminAuditAction.NOTICE_UPDATED, id.toString(), request.reason, before, state(row))
        return result
    }
    @Transactional
    fun publish(actor: Long, id: UUID, request: PublishNoticeRequest): AdminNoticeResponse {
        if (request.notify && request.notificationKind == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "알림 유형을 지정하세요.")
        val row = lock(id)
        if (row.status != NoticeStatus.DRAFT) conflict("이미 게시된 공지입니다.")
        val before = state(row); val now = now()
        row.status = NoticeStatus.PUBLISHED; row.publishedAt = now; row.updatedAt = now
        val notified = request.notify && row.notifiedAt == null
        if (notified) {
            pushes.request(row.id, row.title, notificationBody(row.body), request.notificationKind!!, now)
            row.notifiedAt = now
        }
        notices.flush()
        val result = detail(id).copy(notified = notified)
        // 대상 변경·잠금이 모두 끝난 뒤 처리자를 재검증하고 같은 트랜잭션의 감사를 기록한다.
        audits.recordChange(actor, AdminAuditAction.NOTICE_PUBLISHED, id.toString(), request.reason, before, state(row))
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                val fields = mutableMapOf<String, Any?>("notice_id" to id.toString(), "notified" to notified)
                if (notified) fields["notification_kind"] = request.notificationKind!!.name
                logger.event("notice_published", fields)
            }
        })
        return result
    }
    @Transactional
    fun unpublish(actor: Long, id: UUID, reason: String): AdminNoticeResponse {
        val row = lock(id)
        if (row.status != NoticeStatus.PUBLISHED) conflict("게시되지 않은 공지입니다.")
        val before = state(row)
        row.status = NoticeStatus.DRAFT; row.updatedAt = now()
        notices.flush()
        val result = detail(id)
        audits.recordChange(actor, AdminAuditAction.NOTICE_UNPUBLISHED, id.toString(), reason, before, state(row))
        return result
    }
    @Transactional
    fun delete(actor: Long, id: UUID, reason: String) {
        val row = lock(id)
        if (row.status != NoticeStatus.DRAFT || row.notifiedAt != null) conflict("삭제할 수 없는 공지입니다.")
        val before = state(row)
        notices.delete(row); notices.flush()
        audits.recordChange(actor, AdminAuditAction.NOTICE_DELETED, id.toString(), reason, before = before)
    }
    private fun state(row: Notice) = mapper.createObjectNode().put("title", row.title).put("status", row.status.name)
    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun lock(id: UUID) = notices.lockByPublicId(id) ?: missing()
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다.")
    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
    companion object {
        fun notificationBody(body: String): String = body.replace(Regex("\\r\\n|\\r|\\n"), " ").take(300)
    }
}
