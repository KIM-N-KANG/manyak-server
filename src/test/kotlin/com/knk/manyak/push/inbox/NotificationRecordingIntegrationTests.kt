package com.knk.manyak.push.inbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.outbox.PushOutboxRelay
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.service.AttendanceReminderService
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.submission.StoryModerationCompleted
import com.knk.manyak.story.submission.SubmissionStatus
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.service.SimpleStoryCreationService
import org.mockito.Mockito.*
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.slf4j.MDC
import com.knk.manyak.global.observability.MdcKeys
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@ActiveProfiles("test")
abstract class NotificationRecordingIntegrationTests {
    @MockitoBean(name = "pushExecutor") private lateinit var pushExecutor: java.util.concurrent.Executor
    @MockitoBean private lateinit var sender: FcmPushSender
    @MockitoBean private lateinit var outbox: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    @MockitoSpyBean private lateinit var inbox: UserNotificationStore
    @Autowired private lateinit var creation: SimpleStoryCreationService
    @Autowired private lateinit var sessions: StoryCreationSessionRepository
    @Autowired private lateinit var storylines: StoryCreationStorylineRepository
    @MockitoSpyBean private lateinit var requests: StoryCreationRequestRepository
    @Autowired private lateinit var noticeService: com.knk.manyak.notice.service.NoticeService
    @Autowired private lateinit var campaignService: com.knk.manyak.push.service.AdminCampaignService
    @Autowired private lateinit var notices: com.knk.manyak.notice.repository.NoticeRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var recorder: DomainNotificationRecorder
    @Autowired private lateinit var withdrawal: com.knk.manyak.user.service.UserWithdrawalService
    @Autowired private lateinit var tx: TransactionTemplate
    @Autowired private lateinit var attendance: AttendanceReminderService
    @Autowired private lateinit var promotion: PromotionPushService
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var wallet: CreditWalletService
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var user: User

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        MDC.put(MdcKeys.REQUEST_ID, "inbox-recording-test")
        // 커밋 후 작업도 테스트 안에서 끝내 다음 테스트의 발송 대역에 섞이지 않게 한다.
        doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(pushExecutor).execute(any(Runnable::class.java))
        user = member(marketing = true)
    }

    @AfterEach fun clearRequestContext() { MDC.remove(MdcKeys.REQUEST_ID) }

    @Test fun `완성은 푸시 설정과 토큰 없이 도메인 트랜잭션에서 기록하고 재처리를 무시한다`() {
        val event = StoryCompletedEvent(user.id, UUID.randomUUID(), UUID.randomUUID().toString(), "달빛")
        tx.executeWithoutResult {
            recorder.completed(event)
            assertThat(rows()).hasSize(1)
            recorder.completed(event)
        }
        val row = rows().single()
        assertThat(row["type"]).isEqualTo("STORY_COMPLETED")
        assertThat(row["category"]).isEqualTo("ACTIVITY")
        assertThat(row["title"]).isEqualTo("스토리가 완성됐어요")
        assertThat(row["body"]).isEqualTo("달빛")
        assertThat(row["story_public_id"].toString()).isEqualTo(event.storyPublicId)
        assertThat(row["read_at"]).isNull()
        assertThat(row["deep_link"]).isEqualTo("https://manyak.app/stories/${event.storyPublicId}?utm_source=inbox&utm_medium=inbox&utm_campaign=story_completed&utm_content=story_completed")
    }

    @Test fun `도메인 트랜잭션이 롤백되면 내역도 남지 않는다`() {
        assertThatThrownBy {
            tx.executeWithoutResult {
                recorder.completed(StoryCompletedEvent(user.id, UUID.randomUUID(), UUID.randomUUID().toString(), "롤백"))
                error("domain failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(rows()).isEmpty()
    }

    @Test fun `검수 결과와 재검수는 회차별로 결과 문구와 분류를 저장한다`() {
        val submission = UUID.randomUUID().toString()
        listOf(SubmissionStatus.APPROVED, SubmissionStatus.REJECTED, SubmissionStatus.FAILED).forEachIndexed { index, status ->
            val event = StoryModerationCompleted(user.id, submission, null, status, index + 1, "달빛")
            tx.executeWithoutResult { recorder.moderated(event); recorder.moderated(event) }
        }
        assertThat(rows().map { it["title"] }).containsExactly("검수를 통과했어요", "검수에서 반려됐어요", "검수를 진행하지 못했어요")
        assertThat(rows().map { it["body"] }).containsExactly("「달빛」의 등록이 완료됐어요. 지금 확인해 보세요.", "「달빛」의 내용을 수정해 다시 제출해 주세요.", "「달빛」의 검수 중 문제가 생겼어요. 잠시 후 다시 제출해 주세요.")
        assertThat(rows().map { it["category"] }).containsOnly("ACTIVITY")
        assertThat(rows().map { it["story_public_id"] }).containsOnlyNulls()
        assertThat(rows().map { it["deep_link"] }).containsOnly("https://manyak.app/studio?utm_source=inbox&utm_medium=inbox&utm_campaign=story_moderation_completed&utm_content=story_moderation_completed")
    }

    @Test fun `출석은 토큰과 야간 동의 없이 기록하고 미동의 수령 정지 탈퇴는 제외하며 반복에 멱등이다`() {
        member(marketing = false)
        member(status = UserStatus.SUSPENDED)
        member(status = UserStatus.DELETED)
        val attended = member()
        val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
        wallet.reward(attended.id, 100, CreditReason.ATTENDANCE_REWARD, "attendance:${attended.id}:$date")
        attendance.sendReminders()
        attendance.sendReminders()
        val row = rows().single()
        assertThat(row["user_id"]).isEqualTo(user.id)
        assertThat(row["message_key"]).isEqualTo("attendance:${user.publicId}:$date")
        assertThat(row["type"]).isEqualTo("ATTENDANCE_REMINDER")
        assertThat(row["category"]).isEqualTo("ACTIVITY")
        assertThat(row["title"]).isEqualTo("이프 250이 도착했어요 🍃")
        assertThat(row["deep_link"].toString()).contains("?tab=free&utm_source=inbox&utm_medium=inbox")
    }

    @Test fun `서비스 캠페인은 활성 전원 광고 캠페인은 광고 동의자에게만 기록하고 재실행은 중복하지 않는다`() {
        val noConsent = member(marketing = false)
        member(status = UserStatus.SUSPENDED)
        member(status = UserStatus.DELETED)
        val service = campaigns.save(PushCampaign(title = "소식", body = "본문", kind = PushKind.SERVICE, scheduledAt = Instant.EPOCH))
        val ad = campaigns.save(PushCampaign(title = "홍보", body = "본문", scheduledAt = Instant.EPOCH))
        promotion.sendDue(Instant.now())
        assertThat(rows()).hasSize(3)
        assertThat(rows().filter { it["type"] == "NOTICE" }.map { it["user_id"] }).containsExactlyInAnyOrder(user.id, noConsent.id)
        val adRow = rows().single { it["type"] == "PROMOTION" }
        assertThat(adRow["title"]).isEqualTo("홍보")
        assertThat(adRow["deep_link"]).isNull()
        assertThat(rows().map { it["category"] }).containsOnly("NEWS")
        listOf(service, ad).forEach { it.status = PushCampaignStatus.SCHEDULED; campaigns.saveAndFlush(it) }
        promotion.sendDue(Instant.now())
        assertThat(rows()).hasSize(3)
    }

    @Test fun `취소 캠페인은 내역을 만들지 않는다`() {
        campaigns.save(PushCampaign(title = "취소", body = "본문", scheduledAt = Instant.EPOCH, status = PushCampaignStatus.CANCELED))
        promotion.sendDue(Instant.now())
        assertThat(rows()).isEmpty()
    }


    @Test fun `실제 완성 요청과 replay는 내역 한 건과 완료 상태를 함께 커밋한다`() {
        val request = creationRequest(user.id)
        doAnswer { call ->
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.COMPLETED)
            call.callRealMethod()
        }.`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox)).insert(any(UserNotification::class.java) ?: placeholder())
        creation.createSimpleStory(request, user.id)
        creation.createSimpleStory(request, user.id)
        assertThat(rows()).hasSize(1)
        assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.COMPLETED)
    }

    @Test fun `내역 삽입 실패는 실제 완성 요청의 완료 기록도 롤백한다`() {
        val request = creationRequest(user.id)
        doThrow(IllegalStateException("inbox unavailable")).`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox)).insert(any(UserNotification::class.java) ?: placeholder())
        assertThatThrownBy { creation.createSimpleStory(request, user.id) }
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException::class.java)
            .hasRootCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("inbox unavailable")
        assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.PENDING)
        assertThat(rows()).isEmpty()
    }

    @Test fun `게스트 완성은 내역을 기록하지 않는다`() {
        creation.createSimpleStory(creationRequest(null), null, UUID.randomUUID().toString())
        assertThat(rows()).isEmpty()
    }

    @Test fun `내역 기록 실패는 캠페인을 실패로 두고 발송하지 않는다`() {
        campaigns.save(PushCampaign(title = "실패", body = "본문", scheduledAt = Instant.EPOCH))
        doThrow(IllegalStateException("inbox unavailable")).`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox)).insertScheduled(
            any(NotificationContent::class.java) ?: NotificationContent(NotificationType.PROMOTION, "", "", null),
            anyString(), anyString(), any(Instant::class.java) ?: Instant.EPOCH)
        assertThat(promotion.sendDue(Instant.now()).single().failed).isTrue()
        assertThat(campaigns.findAll().single().status).isEqualTo(PushCampaignStatus.FAILED)
        assertThat(rows()).isEmpty()
        verifyNoInteractions(sender, outbox)
    }

    @Test fun `배포 전 공지는 소급하지 않고 공지 링크 태그와 캠페인 UTM은 사본으로 남는다`() {
        val notice = notices.save(com.knk.manyak.notice.entity.Notice(title = "공지", body = "내용", tag = com.knk.manyak.notice.entity.NoticeTag.UPDATE,
            status = com.knk.manyak.notice.entity.NoticeStatus.PUBLISHED, createdByUserId = user.id))
        promotion.sendDue(Instant.now())
        assertThat(rows()).isEmpty()
        val campaign = campaigns.save(PushCampaign(title = notice.title, body = notice.body, kind = PushKind.SERVICE,
            noticeId = notice.id, noticeTag = notice.tag, scheduledAt = Instant.EPOCH))
        promotion.sendDue(Instant.now())
        notice.status = com.knk.manyak.notice.entity.NoticeStatus.DRAFT
        notice.title = "바뀐 공지"
        notices.saveAndFlush(notice)
        val row = rows().single()
        assertThat(row["title"]).isEqualTo("공지")
        assertThat(row["notice_public_id"]).isEqualTo(notice.publicId)
        assertThat(row["notice_tag"]).isEqualTo("UPDATE")
        assertThat(row["deep_link"]).isEqualTo("https://manyak.app/notices/${notice.publicId}?utm_source=inbox&utm_medium=inbox&utm_campaign=${campaign.publicId}&utm_content=notice")
    }


    @Test fun `스토리 저장 뒤 탈퇴가 커밋되면 늦은 완성은 내역을 되살리지 않는다`() {
        val request = creationRequest(user.id)
        // 사용자 잠금이 완료 기록 진입부로 이동했으므로 그 전의 비잠금 소유자 조회에서 탈퇴를 먼저 커밋한다.
        doAnswer { call ->
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try { pool.submit { withdrawal.withdraw(user.id) }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            finally { pool.shutdownNow() }
            // Spring Data 쿼리 메서드는 추상 메서드이므로 spy에서 callRealMethod 대신 동일한 비잠금 조회를 실행한다.
            jdbc.queryForObject("SELECT user_id FROM story_creation_requests WHERE id=?", Long::class.java, call.getArgument<Long>(0))
        }.`when`(requests).findUserIdById(anyLong())
        creation.createSimpleStory(request, user.id)
        assertThat(users.findById(user.id).orElseThrow().status).isEqualTo(UserStatus.DELETED)
        assertThat(requests.findByRequestId(request.requestId)!!.status).isEqualTo(StoryCreationRequestStatus.COMPLETED)
        assertThat(rows()).isEmpty()
    }

    @Test fun `완성이 사용자 잠금을 잡으면 탈퇴는 커밋을 기다린 뒤 새 내역도 지운다`() {
        val request = creationRequest(user.id)
        val recording = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val withdrawing = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        doAnswer { call ->
            recording.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            call.callRealMethod()
        }.`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox))
            .insert(any(UserNotification::class.java) ?: placeholder())
        try {
            val completion = pool.submit { creation.createSimpleStory(request, user.id) }
            check(recording.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val deletion = pool.submit { withdrawing.countDown(); withdrawal.withdraw(user.id) }
            check(withdrawing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertThatThrownBy { deletion.get(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            release.countDown()
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS)
            deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(rows()).isEmpty()
            assertThat(users.findById(user.id).orElseThrow().status).isEqualTo(UserStatus.DELETED)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `출석 내역 기록 실패는 발송 전에 회차를 중단한다`() {
        doThrow(IllegalStateException("inbox unavailable"))
            .`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox)).insertScheduled(
                any(NotificationContent::class.java) ?: NotificationContent(NotificationType.ATTENDANCE_REMINDER, "", "", null),
                anyString(), anyString(), any(Instant::class.java) ?: Instant.EPOCH)
        assertThatThrownBy { attendance.sendReminders() }
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException::class.java)
            .hasRootCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("inbox unavailable")
        assertThat(rows()).isEmpty()
        verifyNoInteractions(sender, outbox)
    }

    @Test fun `기록기는 트랜잭션 없는 호출을 거절한다`() {
        assertThatThrownBy { inbox.insert(placeholder()) }
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
    }


    @Test fun `PROMOTION 공지는 게시로 만든 캠페인에서 동의자만 태그와 원문 제목을 기록한다`() {
        member(marketing = false)
        val notice = publishNotice(com.knk.manyak.notice.entity.NoticeTag.PROMOTION)
        promotion.sendDue(Instant.now())
        val row = rows().single()
        assertThat(row["type"]).isEqualTo("PROMOTION")
        assertThat(row["category"]).isEqualTo("NEWS")
        assertThat(row["notice_tag"]).isEqualTo("PROMOTION")
        assertThat(row["notice_public_id"]).isEqualTo(notice.noticeId)
        assertThat(row["title"]).isEqualTo("알림 제목")
        assertThat(row["body"]).isEqualTo("직접 쓴 알림 본문\n줄바꿈")
    }

    @Test fun `정보성 공지는 서비스 설정과 토큰 광고 동의 없이 활성 회원 셋에게 태그를 남긴다`() {
        member(marketing = false)
        users.save(User(nickname = "서비스 동의", servicePushEnabled = true))
        member(status = UserStatus.SUSPENDED)
        member(status = UserStatus.DELETED)
        val notice = publishNotice(com.knk.manyak.notice.entity.NoticeTag.UPDATE)
        promotion.sendDue(Instant.now())
        assertThat(rows()).hasSize(3)
        assertThat(rows().map { it["type"] }).containsOnly("NOTICE")
        assertThat(rows().map { it["notice_tag"] }).containsOnly("UPDATE")
        assertThat(rows().map { it["notice_public_id"] }).containsOnly(notice.noticeId)
        assertThat(rows().map { it["title"] }).containsOnly("알림 제목")
        assertThat(rows().map { it["body"] }).containsOnly("직접 쓴 알림 본문\n줄바꿈")
    }

    @Test fun `공지 캠페인 취소 뒤 해제와 재게시도 캠페인과 내역을 새로 만들지 않는다`() {
        val notice = publishNotice(com.knk.manyak.notice.entity.NoticeTag.SERVICE)
        campaignService.cancel(user.id, campaigns.findAll().single().publicId, "취소")
        assertThat(notices.findByPublicId(notice.noticeId)!!.status.name).isEqualTo("PUBLISHED")
        assertThat(campaigns.findAll().single().status).isEqualTo(PushCampaignStatus.CANCELED)
        noticeService.unpublish(user.id, notice.noticeId, "해제")
        assertThat(noticeService.publish(user.id, notice.noticeId, com.knk.manyak.notice.dto.PublishNoticeRequest("재게시")).notified).isFalse()
        promotion.sendDue(Instant.now())
        assertThat(campaigns.findAll()).hasSize(1)
        assertThat(rows()).isEmpty()
    }

    @Test fun `내역 기록 후 공지 수정과 재게시는 최초 제목 태그와 회원별 한 행을 보존한다`() {
        val notice = publishNotice(com.knk.manyak.notice.entity.NoticeTag.UPDATE)
        promotion.sendDue(Instant.now())
        val original = rows().single()
        noticeService.update(user.id, notice.noticeId, com.knk.manyak.notice.dto.UpdateNoticeRequest(
            title = "수정한 제목", tag = com.knk.manyak.notice.entity.NoticeTag.EVENT, reason = "수정"))
        noticeService.unpublish(user.id, notice.noticeId, "해제")
        noticeService.publish(user.id, notice.noticeId, com.knk.manyak.notice.dto.PublishNoticeRequest("재게시"))
        promotion.sendDue(Instant.now())
        assertThat(campaigns.findAll()).hasSize(1)
        assertThat(rows()).containsExactly(original)
    }

    private fun publishNotice(tag: com.knk.manyak.notice.entity.NoticeTag): com.knk.manyak.notice.dto.AdminNoticeResponse {
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", user.id)
        val notice = noticeService.create(user.id, com.knk.manyak.notice.dto.CreateNoticeRequest(title = "원문 제목", body = "본문", tag = tag, notificationTitle = "알림 제목", notificationBody = "직접 쓴 알림 본문\n줄바꿈", reason = "작성"))
        return noticeService.publish(user.id, notice.noticeId, com.knk.manyak.notice.dto.PublishNoticeRequest("게시"))
    }

    private fun creationRequest(owner: Long?): CreateSimpleStoryRequest {
        val session = sessions.save(StoryCreationSession(userId = owner, status = StoryCreationSessionStatus.STORYLINES_GENERATED))
        val storyline = storylines.save(StoryCreationStoryline(creationSession = session, storylineText = "예시 스토리라인", storylineOrder = 1))
        return CreateSimpleStoryRequest(requestId = UUID.randomUUID(), simpleCreationId = session.id, storylineId = storyline.id, additionalInfos = emptyList())
    }
    private fun placeholder() = UserNotification(userId = user.id, messageKey = "", type = NotificationType.STORY_COMPLETED, title = "", body = "")

    private fun member(status: UserStatus = UserStatus.ACTIVE, marketing: Boolean = true) = users.save(
        User(nickname = UUID.randomUUID().toString().take(8), status = status, servicePushEnabled = false,
            marketingPushAgreedAt = if (marketing) Instant.EPOCH else null),
    )
    private fun rows(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM user_notifications ORDER BY id")
}

@SpringBootTest(properties = ["manyak.push.mode=local", "manyak.ai.story.stub=true"])
class LocalNotificationRecordingIntegrationTests : NotificationRecordingIntegrationTests()

@SpringBootTest(properties = ["manyak.push.mode=remote", "manyak.ai.story.stub=true"])
class RemoteNotificationRecordingIntegrationTests : NotificationRecordingIntegrationTests()
