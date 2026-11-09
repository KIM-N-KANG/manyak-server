package com.knk.manyak.support

import com.knk.manyak.admin.AdminProbeConfig
import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.admin.service.AdminEligibilityService
import com.knk.manyak.auth.consent.ConsentTokenStore
import com.knk.manyak.auth.controller.FakeSocialLoginConfig
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.service.ChatGenerationEvents
import com.knk.manyak.chat.service.ChatGenerationReceivers
import com.knk.manyak.chat.service.ChatGenerationStore
import com.knk.manyak.chat.service.ChatRealtimeImageService
import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.credit.support.H2CreditAdminLocksConfig
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.migration.controller.MigrationIndexEventTestConfig
import com.knk.manyak.push.inbox.DomainNotificationRecorder
import com.knk.manyak.push.inbox.UserNotificationStore
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.OfficialStoryAccount
import com.knk.manyak.story.submission.SlackSubmissionHeldNotifier
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.concurrent.Executor

/**
 * 통합 테스트 공용 루트(기본 조합 D, KNK-2053).
 *
 * Spring 테스트 컨텍스트 캐시 키는 클래스에 붙은 설정(속성·`@Import`·목·스파이·중첩 설정·`@DynamicPropertySource`)을
 * 전부 합친 값이다. 클래스마다 조금씩 달라 키가 106개로 갈렸고, 캐시 상한(32)을 넘어 축출·재기동까지 생겼다.
 * 그래서 통합 테스트는 직접 `@SpringBootTest`를 달지 않고 이 클래스나 그 하위 베이스를 상속한다
 * (가드: `SpringBootTestBaseGuardTests`). 같은 베이스를 상속한 클래스는 키가 같아 컨텍스트 하나를 공유한다.
 *
 * 여기 두는 것은 **다른 테스트에 중립인 것**뿐이다.
 * - 스파이는 기본 동작이 실제 빈 그대로이고 테스트마다 자동 reset된다. 스텁이 필요한 테스트는 `@Autowired`로 받아 쓴다.
 *   클래스마다 필요한 스파이만 달면 그 조합만큼 키가 갈리므로, 스파이는 전부 여기 합쳐 둔다.
 * - executor는 스파이다. 예전에 executor를 목으로 바꿔 작업을 멈추던 테스트는 `@BeforeEach`에서
 *   `doNothing().when(executor).execute(any())`로 같은 동작을 재현한다.
 * - `@Import`한 설정은 테스트 전용 엔드포인트·캡처·H2 대체 구현처럼 다른 테스트의 경로를 바꾸지 않는 것만 둔다.
 * - 속성은 꺼져 있던 기능을 켜기만 하는 값(내부 API·웹훅 시크릿·공식 계정 id·스텁 AI)이다. 꺼진 상태를 검증하는
 *   테스트는 [AlternateConfigIntegrationTest]에 모은다.
 * 클래스마다 달라야 하는 동작은 이 설정을 늘리지 말고 토글 가능한 페이크와 `@AfterEach` 원복으로 바꾼다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "manyak.ai.story.stub=true",
        "manyak.asset.image-base-url=https://cdn.test",
        "manyak.official-user-public-id=${IntegrationTest.OFFICIAL_USER_PUBLIC_ID}",
        "manyak.internal.shared-secret=${IntegrationTest.INTERNAL_SHARED_SECRET}",
        "manyak.payment.groble.webhook-secret=${IntegrationTest.GROBLE_WEBHOOK_SECRET}",
    ],
)
@Import(
    FakeSocialLoginConfig::class,
    H2CreditAdminLocksConfig::class,
    AnalyticsCaptureConfig::class,
    AdminProbeConfig::class,
    MigrationIndexEventTestConfig::class,
    SubmissionApprovalTestSupport::class,
)
@MockitoSpyBean(
    types = [
        UserRepository::class,
        SocialAccountRepository::class,
        StoryRepository::class,
        StoryChatRepository::class,
        StoryMessageRepository::class,
        AdminAuditLogRepository::class,
        CreditPolicyRepository::class,
        PushCampaignRepository::class,
        UserNotificationStore::class,
        DomainNotificationRecorder::class,
        StoryCreationRequestRepository::class,
        ConsentTokenStore::class,
        AdminEligibilityService::class,
        OfficialStoryAccount::class,
        SlackSubmissionHeldNotifier::class,
        ServerAnalytics::class,
        StructuredLogger::class,
        ChatGenerationEvents::class,
        ChatGenerationReceivers::class,
        ChatGenerationStore::class,
        ChatRealtimeImageService::class,
        StringRedisTemplate::class,
    ],
)
@MockitoSpyBean(name = "capturingAnalyticsEventPublisher", types = [AnalyticsEventPublisher::class])
@MockitoSpyBean(name = "storyModerationExecutor", types = [Executor::class])
@MockitoSpyBean(name = "pushExecutor", types = [Executor::class])
@MockitoSpyBean(name = "chatSseExecutor", types = [Executor::class])
abstract class IntegrationTest {
    companion object {
        const val OFFICIAL_USER_PUBLIC_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val INTERNAL_SHARED_SECRET = "internal-push-test-secret"
        const val GROBLE_WEBHOOK_SECRET = "test-only-groble-secret"

        /**
         * 환경변수(MANYAK_SLACK_FEEDBACK_WEBHOOK_URL·MANYAK_GOOGLE_FORM_FEEDBACK_ID)가 export돼 있어도 빈 값을 강제해
         * 외부 발송을 막는다. env는 yml보다 우선하므로 test yml만으로는 막히지 않는다. 하위 클래스 전체가 이 메서드
         * 하나를 공유하므로 컨텍스트 키를 가르지 않는다.
         */
        @JvmStatic
        @DynamicPropertySource
        fun blockExternalSenders(registry: DynamicPropertyRegistry) {
            registry.add("manyak.slack.feedback-webhook-url") { "" }
            registry.add("manyak.google-form.feedback.form-id") { "" }
        }
    }
}
