package com.knk.manyak.push.inbox

import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "Notifications", description = "회원 알림 내역")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/notifications")
class UserNotificationController(private val service: UserNotificationService) {
    @Operation(summary = "알림 내역 목록", description = "최신순 UUID 커서 목록. limit은 기본 20, 1~50으로 보정합니다. 정지 403, 탈퇴 401입니다.")
    @GetMapping
    fun list(
        @CurrentUserId userId: Long?,
        @RequestParam(required = false) category: NotificationCategory?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: UUID?,
    ): NotificationPage = service.list(userId, category, limit, cursor)

    @Operation(summary = "분류별 안 읽은 알림 수")
    @GetMapping("/unread-count")
    fun unreadCount(@CurrentUserId userId: Long?): NotificationUnreadCount = service.unreadCount(userId)

    @Operation(summary = "알림 읽음", description = "최초 읽은 시각을 유지합니다. 없거나 타인 소유면 404입니다.")
    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@CurrentUserId userId: Long?, @PathVariable notificationId: UUID) = service.read(userId, notificationId)

    @Operation(summary = "모든 알림 읽음", description = "category를 생략하면 전체, 지정하면 해당 분류만 읽음 처리합니다.")
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun readAll(@CurrentUserId userId: Long?, @RequestParam(required = false) category: NotificationCategory?) = service.readAll(userId, category)
}
