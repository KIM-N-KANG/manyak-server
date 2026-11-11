package com.knk.manyak.auth.service

import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.push.dto.PushPlatform
import com.knk.manyak.push.dto.PushSettingsUpdateRequest
import com.knk.manyak.push.inbox.DomainNotificationRecorder
import com.knk.manyak.push.service.DevicePushTokenService
import com.knk.manyak.story.draft.PutStoryDraftRequest
import com.knk.manyak.story.draft.StoryDraftMode
import com.knk.manyak.story.draft.StoryDraftService
import com.knk.manyak.story.draft.StoryDraftStage
import com.knk.manyak.story.dto.StoryCreationStage
import com.knk.manyak.story.dto.StoryTakedownReason
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.StoryCreationRequestRecorder
import com.knk.manyak.story.submission.AdminModerationService
import com.knk.manyak.story.submission.StorySubmission
import com.knk.manyak.story.submission.StorySubmissionRepository
import com.knk.manyak.story.submission.StorySubmissionService
import com.knk.manyak.story.submission.StoryTakedownRequest
import com.knk.manyak.story.submission.StoryTakedownService
import com.knk.manyak.story.submission.SubmissionKind
import com.knk.manyak.story.submission.SubmissionTransactions
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.IntegrationTest
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import net.ttddyy.observation.tracing.QueryContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.util.Collections
import java.util.UUID

/**
 * 계정 행 잠금 경로의 잠금 SQL 순서(KNK-2056 A-3a). 각 경로에서 `FOR UPDATE` 문장이 어느 테이블을 어떤 순서로 잠그는지
 * 고정한다. 잠금 순서 규칙은 ACCOUNT, STORY, MODERATION, NOTIFICATION 5-2가 정한다. 같은 스레드의 SQL만 모은다.
 */
class AccountLockOrderIntegrationTests : IntegrationTest() {
    @Autowired private lateinit var registry: ObservationRegistry
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var submissions: StorySubmissionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var drafts: StoryDraftService
    @Autowired private lateinit var recorder: StoryCreationRequestRecorder
    @Autowired private lateinit var moderation: AdminModerationService
    @Autowired private lateinit var submissionService: StorySubmissionService
    @Autowired private lateinit var takedown: StoryTakedownService
    @Autowired private lateinit var submissionTransactions: SubmissionTransactions
    @Autowired private lateinit var notifications: DomainNotificationRecorder
    @Autowired private lateinit var pushTokens: DevicePushTokenService
    @Autowired private lateinit var locks: AccountLockService
    @Autowired private lateinit var invites: com.knk.manyak.invite.service.InviteService
    @Autowired private lateinit var migration: com.knk.manyak.migration.service.GuestDataMigrationService
    @Autowired private lateinit var pushSettings: com.knk.manyak.push.service.PushSettingsService
    @Autowired private lateinit var chats: com.knk.manyak.chat.repository.StoryChatRepository
    private val mapper = ObjectMapper()
    private lateinit var member: User

    @BeforeEach fun setUp() {
        cleaner.cleanAll()
        LockSql.install(registry)
        member = users.save(User(nickname = "잠금회원", status = UserStatus.ACTIVE))
    }

    @Test fun `초안 저장과 삭제는 회원 행 다음 초안 행을 다룬다`() {
        val id = UUID.randomUUID()
        val request = PutStoryDraftRequest(StoryDraftMode.GENERAL, StoryDraftStage.GENERAL_DRAFT, 1, mapper.readTree("{}"))
        assertThat(LockSql.tables { drafts.put(member.id, id.toString(), request) }).containsExactly("users")
        assertThat(LockSql.tables { drafts.put(member.id, id.toString(), request.copy(baseVersion = 1)) }).containsExactly("users")
        assertThat(LockSql.tables { drafts.delete(member.id, id.toString()) }).containsExactly("users")
    }

    @Test fun `초안을 지우는 생성 요청은 회원 행, 요청 행 순서로 잠그고 완성 알림 내역이 회원 행을 다시 잠근다`() {
        val requestId = UUID.randomUUID()
        val draftId = UUID.randomUUID()
        fun run() = recorder.execute(requestId, StoryCreationStage.STORY_COMPLETION, member.id, null, String::class.java,
            block = { _, _, _ -> "완성" },
            onCompleted = { events.publishEvent(StoryCompletedEvent(member.id, requestId, UUID.randomUUID().toString(), "제목")) },
            draftId = draftId)
        // 삽입(회원 잠금 → 요청 삽입 → 초안 삭제), 상태 갱신(회원 → 요청 행), 같은 트랜잭션의 완성 알림 내역(회원).
        assertThat(LockSql.tables { run() }).containsExactly("users", "users", "story_creation_requests", "users")
        // 완료된 요청의 재요청: 삽입 시도(회원 잠금 뒤 유니크 위반으로 롤백), 기존 행 해석(회원 → 요청 행) 뒤 저장 결과를 돌려준다.
        assertThat(LockSql.tables { run() }).containsExactly("users", "users", "story_creation_requests")
    }

    @Test fun `검수 보류 해제는 처리자 회원 행을 제출본보다 먼저 잠근다`() {
        val admin = users.save(User(nickname = "관리자"))
        assertThat(LockSql.tables { runCatching { moderation.release(admin.id, UUID.randomUUID(), "사유") } })
            .containsExactly("users", "story_submissions")
    }

    @Test fun `제출본 삭제는 회원, 스토리, 제출본 순서로 잠근다`() {
        val story = stories.save(Story(title = "수정 대상", userId = member.id))
        val row = submissions.save(StorySubmission(userId = member.id, storyId = story.id, kind = SubmissionKind.UPDATE, payload = "{}"))
        assertThat(LockSql.tables { submissionService.delete(row.publicId.toString(), member.id) })
            .containsExactly("users", "stories", "story_submissions")
    }

    @Test fun `검수 실행은 회원, 스토리, 제출본 순서로 잠근다`() {
        val story = stories.save(Story(title = "수정 대상", userId = member.id))
        val row = submissions.save(StorySubmission(userId = member.id, storyId = story.id, kind = SubmissionKind.UPDATE, payload = "{}"))
        assertThat(LockSql.tables { submissionTransactions.start(row.id, row.attempt) })
            .containsExactly("users", "stories", "story_submissions")
    }

    @Test fun `공개 중단은 소유 회원, 스토리, 제출본 순서로 잠근 뒤 처리자를 잠근다`() {
        val admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val story = stories.save(Story(title = "공개 스토리", userId = member.id))
        // 감사 기록은 요청 상관 ID를 요구한다(HTTP 필터가 넣는 값).
        org.slf4j.MDC.put("request_id", "lock-order")
        val tables = try {
            LockSql.tables { takedown.takeDown(admin.id, story.publicId, StoryTakedownRequest(StoryTakedownReason.HATE_SPEECH, memo = "검증")) }
        } finally {
            org.slf4j.MDC.remove("request_id")
        }
        assertThat(tables).isEqualTo(TAKEDOWN_ORDER)
    }

    @Test fun `완성 알림 내역 기록은 회원 행을 잠근다`() {
        val tables = LockSql.tables {
            TransactionTemplate(transactionManager).executeWithoutResult {
                notifications.completed(StoryCompletedEvent(member.id, UUID.randomUUID(), UUID.randomUUID().toString(), "제목"))
            }
        }
        assertThat(tables).containsExactly("users")
    }

    @Test fun `푸시 토큰 등록과 삭제는 회원 행 다음 토큰 행을 잠근다`() {
        assertThat(LockSql.tables { pushTokens.register(member.id, "lock-order-token", PushPlatform.ANDROID) })
            .containsExactly("users", "device_push_tokens")
        assertThat(LockSql.tables { pushTokens.unregister(member.id, "lock-order-token") }).containsExactly("users")
    }

    @Test fun `초대 코드 발급은 회원 행을 잠그고 처음 발급할 때만 커밋에서 회원 행을 갱신한다`() {
        // 처음 발급: 잠금 → 코드 중복 확인 → 커밋 때 갱신.
        assertThat(LockSql.usersAndLocks { invites.getOrCreateInvite(member.id) }).containsExactly("lock:users", "select:users", "update:users")
        assertThat(LockSql.usersAndLocks { invites.getOrCreateInvite(member.id) }).containsExactly("lock:users")
    }

    @Test fun `초대 코드 제출은 제출자 행을 잠그고 초대자를 잠그지 않고 읽은 뒤 지갑을 잠그고 커밋에서 제출자 행을 갱신한다`() {
        val inviter = users.save(User(nickname = "초대자", inviteCode = "LOCK2056"))
        val redeemer = users.save(User(nickname = "제출자"))
        assertThat(LockSql.usersAndLocks { invites.redeem(redeemer.id, inviter.inviteCode!!) }).isEqualTo(REDEEM_ORDER)
    }

    @Test fun `이관은 회원 행을 잠그고 시도 수와 완료 시각을 커밋에서 한 번에 갱신한다`() {
        assertThat(LockSql.usersAndLocks { migration.migrate(member.id, MigrationRequest()) }).containsExactly("lock:users", "update:users")
        val story = stories.save(Story(title = "게스트 스토리"))
        val chat = chats.save(StoryChat(storyId = story.id))
        assertThat(LockSql.usersAndLocks { migration.migrate(member.id, MigrationRequest(chatIds = listOf(chat.publicId.toString()))) })
            .isEqualTo(MIGRATE_WITH_CLAIM_ORDER)
    }

    @Test fun `푸시 설정 조회는 회원 행을 잠그고 변경은 커밋에서 회원 행을 갱신한다`() {
        assertThat(LockSql.usersAndLocks { pushSettings.getSettings(member.id) }).containsExactly("lock:users")
        assertThat(LockSql.usersAndLocks { pushSettings.updateSettings(member.id, PushSettingsUpdateRequest(servicePush = false, marketingPush = true, marketingNightPush = true)) })
            .containsExactly("lock:users", "update:users")
    }

    @Test fun `잠금 진입점은 트랜잭션 밖에서 부르면 잠그지 않고 거부한다`() {
        assertThat(LockSql.tables {
            org.assertj.core.api.Assertions.assertThatThrownBy { locks.lock(member.id) }
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
        }).isEmpty()
        val locked = TransactionTemplate(transactionManager).execute { locks.lock(member.id) }
        assertThat(locked).isEqualTo(com.knk.manyak.auth.dto.AccountStatusView(member.id, member.publicId, UserStatus.ACTIVE))
    }

    private companion object {
        /** 공개 중단: 소유 회원 → 스토리 → 대기 제출본 → 감사 처리자(회원, 관리자 역할). MODERATION 5-2. */
        val TAKEDOWN_ORDER = listOf("users", "stories", "story_submissions", "users", "admin_accounts")

        /** 초대 제출: 제출자 잠금 → 초대자 조회(코드) → 첫 지갑과 로트 → 제출자 갱신(자동 flush) → 둘째 지갑과 로트. */
        val REDEEM_ORDER = listOf("lock:users", "select:users", "lock:credit_wallets", "lock:credit_lots", "update:users",
            "lock:credit_wallets", "lock:credit_lots")

        /** 채팅을 클레임한 이관: 회원 잠금 → 커밋 때 시도 수와 완료 시각을 한 번에 갱신. */
        val MIGRATE_WITH_CLAIM_ORDER = listOf("lock:users", "update:users")
    }
}

/**
 * 측정 구간에 호출 스레드에서 실행된 SQL만 모은다. 핸들러는 공유 컨텍스트의 레지스트리에 한 번 붙고, 측정 중이 아니거나
 * 다른 스레드의 쿼리는 버린다.
 */
private object LockSql {
    private val sql: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var installed: ObservationRegistry? = null
    @Volatile private var measuring: Thread? = null
    private val lock = Regex("\\bfor\\s+(no\\s+key\\s+)?update\\b", RegexOption.IGNORE_CASE)
    private val table = Regex("\\bfrom\\s+\"?(\\w+)\"?", RegexOption.IGNORE_CASE)
    private val usersUpdate = Regex("^\\s*update\\s+users\\b", RegexOption.IGNORE_CASE)
    private val usersSelect = Regex("^\\s*select\\b.*\\bfrom\\s+users\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun install(registry: ObservationRegistry) {
        if (installed === registry) return
        registry.observationConfig().observationHandler(object : ObservationHandler<QueryContext> {
            override fun supportsContext(context: Observation.Context) = context is QueryContext
            override fun onStop(context: QueryContext) {
                if (Thread.currentThread() === measuring) sql.addAll(context.queries)
            }
        })
        installed = registry
    }

    /** 행 잠금 문장의 대상 테이블. */
    fun tables(block: () -> Any?): List<String> =
        run(block).filter { lock.containsMatchIn(it) }.map { table.find(it)!!.groupValues[1].lowercase() }

    /** 행 잠금(`lock:테이블`)과 `users`의 비잠금 조회(`select:users`), 갱신(`update:users`)을 순서대로. */
    fun usersAndLocks(block: () -> Any?): List<String> = run(block).mapNotNull {
        when {
            lock.containsMatchIn(it) -> "lock:" + table.find(it)!!.groupValues[1].lowercase()
            usersUpdate.containsMatchIn(it) -> "update:users"
            usersSelect.containsMatchIn(it) -> "select:users"
            else -> null
        }
    }

    private fun run(block: () -> Any?): List<String> {
        sql.clear()
        measuring = Thread.currentThread()
        try {
            Observation.createNotStarted("account-lock-order", installed!!).observe<Any?> { block() }
        } finally {
            measuring = null
        }
        return synchronized(sql) { sql.toList() }
    }
}
