package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * `handoffCode`를 실은 Google 로그인이 시드와 이관을 함께 수행하는지 검증한다(스펙 §4-3-5 로그인 핸드오프).
 *
 * 별도 consume 엔드포인트는 없다 — 로그인 호출이 소비를 겸한다. 시드를 로그인 뒤로 미루면
 * 디바이스 헤더 없는 첫 로그인이 소진 시드를 비가역으로 확정하기 때문이다(§4-3-7).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LoginHandoffTestConfig::class)
class LoginHandoffConsumptionIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var socialAccountRepository: SocialAccountRepository
    @Autowired private lateinit var guestTrialLimitService: ToggleableGuestTrialLimitService
    @Autowired private lateinit var loginHandoffService: LoginHandoffService
    @Autowired private lateinit var redisTemplate: org.springframework.data.redis.core.StringRedisTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @AfterEach
    fun tearDown() {
        // 시드 실패 토글은 컨텍스트를 공유하므로 테스트마다 원복해 순서 의존을 만들지 않는다.
        guestTrialLimitService.failSnapshot = false
    }

    @Test
    fun `핸드오프 코드를 실은 로그인은 보관된 게스트 데이터를 계정으로 이관한다`() {
        val story = saveStory(ownerId = null)
        val chat = saveChat(ownerId = null)
        val created = createHandoff(
            storyIds = listOf(story.publicId.toString()),
            chatIds = listOf(chat.publicId.toString()),
        )

        loginWithHandoff("google-user-1", created.handoffCode)

        val owner = userRepository.findAll().single()
        assertThat(storyRepository.findById(story.id).get().userId).isEqualTo(owner.id)
        assertThat(storyChatRepository.findById(chat.id).get().userId).isEqualTo(owner.id)

        val status = fetchStatus(created.handoffCode)
        assertThat(status.status).isEqualTo("MIGRATED")
        assertThat(status.migratedStoryIds).containsExactly(story.publicId.toString())
        assertThat(status.migratedChatIds).containsExactly(chat.publicId.toString())
    }

    @Test
    fun `같은 코드로 다시 로그인해도 오류 없이 같은 결과를 유지한다`() {
        val story = saveStory(ownerId = null)
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))

        loginWithHandoff("google-user-2", created.handoffCode)
        loginWithHandoff("google-user-2", created.handoffCode)

        val owner = userRepository.findAll().single()
        assertThat(storyRepository.findById(story.id).get().userId).isEqualTo(owner.id)
        assertThat(fetchStatus(created.handoffCode).migratedStoryIds)
            .containsExactly(story.publicId.toString())
    }

    @Test
    fun `핸드오프의 원본 디바이스 사용량이 헤더 디바이스보다 우선해 회원 체험에 시드된다`() {
        // 인앱 디바이스는 채팅 체험을 모두 소진한 상태다.
        repeat(CHAT_TURN_LIMIT) {
            assertThat(guestTrialLimitService.reserve(IN_APP_DEVICE_ID, GuestTrialLimitService.Counter.CHAT_TURN))
                .isTrue()
        }
        val created = createHandoff(deviceId = IN_APP_DEVICE_ID)

        // 외부 브라우저(사용량 0인 새 디바이스)에서 로그인하지만, 핸드오프의 인앱 디바이스가 우선해야 한다.
        loginWithHandoff("google-user-3", created.handoffCode, deviceId = EXTERNAL_DEVICE_ID)

        val owner = userRepository.findAll().single()
        assertThat(guestTrialLimitService.reserveMember(owner.id, GuestTrialLimitService.Counter.CHAT_TURN))
            .`as`("인앱에서 소진한 체험이 회원 계정으로 이어져야 한다")
            .isFalse()
    }

    @Test
    fun `무효한 핸드오프 코드는 로그인을 막지 않고 헤더 디바이스로 폴백한다`() {
        loginWithHandoff("google-user-4", "존재하지-않는-코드", deviceId = EXTERNAL_DEVICE_ID)

        val owner = userRepository.findAll().single()
        assertThat(owner.memberTrialSeededAt).isNotNull()
        // 헤더 디바이스는 사용량이 없으므로 회원 체험이 남아 있어야 한다(소진 시드가 아니다).
        assertThat(guestTrialLimitService.reserveMember(owner.id, GuestTrialLimitService.Counter.CHAT_TURN))
            .isTrue()
    }

    @Test
    fun `뒤늦게 도착한 중복 소비가 먼저 이관한 결과를 덮어쓰지 않는다`() {
        // 같은 코드를 실은 로그인이 동시에 둘 들어오면 둘 다 PENDING 스냅샷으로 상태 가드를 통과한다.
        // 먼저 이관한 쪽이 계정을 잠그므로 나중 쪽은 migrationClosed(빈 목록)를 받는데, 그 결과로 덮어쓰면
        // status가 이관된 ID를 잃어 인앱이 로컬 정리를 못 한다(그 데이터는 이미 회원 소유라 게스트 조회는 403).
        val story = saveStory(ownerId = null)
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))
        val staleSnapshot = loginHandoffService.find(created.handoffCode)!!

        loginWithHandoff("google-user-7", created.handoffCode)
        val owner = userRepository.findAll().single()
        loginHandoffService.consume(created.handoffCode, staleSnapshot, owner.id)

        val status = fetchStatus(created.handoffCode)
        assertThat(status.status).isEqualTo("MIGRATED")
        assertThat(status.migratedStoryIds).containsExactly(story.publicId.toString())
    }

    @Test
    fun `시드가 실패하면 핸드오프를 소비하지 않고 원본 디바이스 ID를 남긴다`() {
        // 시드는 Redis 장애 시 false를 돌려주고 member_trial_seeded_at을 NULL로 남겨 다음 로그인이 재시도한다.
        // 그 로그인이 핸드오프를 소비해 버리면 보관하던 원본 디바이스 ID가 지워져(보관 규칙), 재시도는 외부
        // 브라우저 디바이스로 시드하게 된다 — 게스트 사용량이 리셋되거나 소진 상태로 잘못 확정된다.
        guestTrialLimitService.failSnapshot = true
        val created = createHandoff(deviceId = IN_APP_DEVICE_ID)

        loginWithHandoff("google-user-10", created.handoffCode, deviceId = EXTERNAL_DEVICE_ID)

        assertThat(userRepository.findAll().single().memberTrialSeededAt).isNull()
        val handoff = loginHandoffService.find(created.handoffCode)
        assertThat(handoff?.status).isEqualTo(LoginHandoffStatus.PENDING)
        assertThat(handoff?.deviceId)
            .`as`("재시도가 원본 디바이스로 시드할 수 있어야 한다")
            .isEqualTo(IN_APP_DEVICE_ID)
    }

    @Test
    fun `옮길 항목이 없는 핸드오프는 이관 시도 상한을 쓰지 않는다`() {
        // 이관 서비스는 배열을 보기 전에 시도 횟수를 올린다. 디바이스만 실은 인앱 로그인이 그 상한(5회)을
        // 갉아먹으면, 나중에 진짜 옮길 데이터를 실은 핸드오프가 평가도 못 받고 MIGRATION_CLOSED가 된다.
        val created = createHandoff()

        loginWithHandoff("google-user-9", created.handoffCode)

        assertThat(userRepository.findAll().single().migrationAttempts).isZero()
        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("MIGRATED")
    }

    @Test
    fun `소비권이 나간 뒤에는 확인 호출이 저장 상태를 바꾸지 않는다`() {
        // 확인 호출은 읽은 스냅샷을 LANDED로 바꿔 쓴다. 그 읽기와 쓰기 사이에 로그인이 소비를 끝내면
        // 종료 상태(이관된 ID·비운 디바이스 ID)를 LANDED로 덮어써 인앱이 로컬 정리를 못 한다.
        // 그 인터리빙을 스레드 없이 재현하기 위해, 소비를 끝낸 뒤 저장 값만 PENDING으로 되돌려
        // "소비권은 나갔는데 저장 상태는 아직 PENDING"인 순간을 만든다. 이때 확인 호출은 쓰지 않아야 한다.
        val keysBefore = handoffKeys()
        val created = createHandoff(storyIds = listOf(saveStory(ownerId = null).publicId.toString()))
        val key = (handoffKeys() - keysBefore).single()
        loginWithHandoff("google-user-8", created.handoffCode)
        // TTL도 함께 되돌린다. 남은 TTL이 없으면 갱신 스크립트가 만료로 보고 쓰지 않아 가드를 검증하지 못한다.
        redisTemplate.opsForValue()
            .set(key, storedJson(key).replace("\"MIGRATED\"", "\"PENDING\""), java.time.Duration.ofMinutes(30))

        restTestClient.get().uri(HANDOFFS_PATH)
            .header(HEADER_HANDOFF_CODE, created.handoffCode)
            .exchange()
            .expectStatus().isOk

        assertThat(fetchStatus(created.handoffCode).status)
            .`as`("소비권이 나갔으므로 확인 호출은 상태를 전이시키지 않아야 한다")
            .isEqualTo("PENDING")
    }

    private fun handoffKeys(): Set<String> = redisTemplate.keys("login_handoff:*").orEmpty()

    private fun storedJson(key: String): String = redisTemplate.opsForValue().get(key)!!

    @Test
    fun `소비하고 나면 원본 디바이스 ID 원문은 보관하지 않는다`() {
        // 소비 결과는 24시간 연장 보관하지만, 디바이스 ID 원문은 핸드오프 수명 동안만 남아야 한다(스펙 §4-3-5).
        // 결과를 오래 들고 있으려고 원문 식별자까지 같이 연장하면 보관 규칙을 어긴다.
        val created = createHandoff()

        loginWithHandoff("google-user-5", created.handoffCode)

        assertThat(loginHandoffService.find(created.handoffCode)?.deviceId)
            .`as`("소비 후에는 디바이스 ID 원문이 남지 않아야 한다")
            .isNullOrEmpty()
    }

    @Test
    fun `소비 후 재로그인은 비워진 디바이스 ID가 아니라 헤더로 폴백한다`() {
        // 소비 시 디바이스 ID를 비우므로, 첫 로그인의 시드가 Redis 장애로 실패해 미시드로 남은 계정이
        // 재로그인하면 빈 값이 "헤더 없음"으로 읽혀 소진 시드가 확정될 수 있다. 빈 값은 폴백 대상이어야 한다.
        val created = createHandoff()
        loginWithHandoff("google-user-6", created.handoffCode)
        val owner = userRepository.findAll().single()
        userRepository.save(owner.apply { memberTrialSeededAt = null })
        guestTrialLimitService.restoreMember(owner.id, GuestTrialLimitService.Counter.CHAT_TURN)

        loginWithHandoff("google-user-6", created.handoffCode, deviceId = EXTERNAL_DEVICE_ID)

        assertThat(guestTrialLimitService.reserveMember(owner.id, GuestTrialLimitService.Counter.CHAT_TURN))
            .`as`("사용량 없는 헤더 디바이스로 폴백해 체험이 남아 있어야 한다")
            .isTrue()
    }

    @Test
    fun `이관이 실패해도 로그인은 막히지 않고 핸드오프는 미소비로 남는다`() {
        // 정지 계정은 이관이 403이다(§4-5 B20). 로그인 자체는 막히지 않아야 FE가 정지 안내를 띄울 수 있고,
        // 핸드오프는 미소비로 남아 만료 전까지 재시도할 수 있어야 한다(스펙 §4-3-5).
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))
        socialAccountRepository.save(
            SocialAccount(
                userId = suspended.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = SUSPENDED_ID_TOKEN,
            ),
        )
        val story = saveStory(ownerId = null)
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))

        loginWithHandoff(SUSPENDED_ID_TOKEN, created.handoffCode)

        assertThat(storyRepository.findById(story.id).get().userId).isNull()
        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("PENDING")
    }

    private fun saveStory(ownerId: Long?): Story =
        storyRepository.save(Story(userId = ownerId, title = "제목"))

    private fun saveChat(ownerId: Long?): StoryChat {
        val story = storyRepository.save(Story(title = "채팅용 스토리"))
        return storyChatRepository.save(StoryChat(userId = ownerId, storyId = story.id))
    }

    private fun createHandoff(
        storyIds: List<String> = emptyList(),
        chatIds: List<String> = emptyList(),
        deviceId: String = IN_APP_DEVICE_ID,
    ): CreatedHandoff = restTestClient.post().uri(HANDOFFS_PATH)
        .header(RequestCorrelationFilter.HEADER_DEVICE_ID, deviceId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            mapOf(
                "storyIds" to storyIds,
                "chatIds" to chatIds,
                "callbackPath" to "/stories",
                "sourceApp" to "instagram",
            ),
        )
        .exchange()
        .expectStatus().isCreated
        .expectBody(CreatedHandoff::class.java)
        .returnResult()
        .responseBody!!

    private fun loginWithHandoff(idToken: String, handoffCode: String, deviceId: String = EXTERNAL_DEVICE_ID) {
        restTestClient.post().uri("/api/v1/auth/login/google")
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, deviceId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("idToken" to idToken, "handoffCode" to handoffCode))
            .exchange()
            .expectStatus().isOk
    }

    private fun fetchStatus(code: String): HandoffStatus = restTestClient.get().uri(STATUS_PATH)
        .header(HEADER_HANDOFF_CODE, code)
        .exchange()
        .expectStatus().isOk
        .expectBody(HandoffStatus::class.java)
        .returnResult()
        .responseBody!!

    data class CreatedHandoff(
        val handoffCode: String = "",
        val handoffId: String = "",
        val expiresAt: String = "",
    )

    data class HandoffStatus(
        val status: String = "",
        val migratedStoryIds: List<String> = emptyList(),
        val migratedChatIds: List<String> = emptyList(),
    )

    private companion object {
        const val HANDOFFS_PATH = "/api/v1/auth/handoffs"
        const val STATUS_PATH = "/api/v1/auth/handoffs/status"
        const val HEADER_HANDOFF_CODE = "X-Manyak-Handoff-Code"
        const val IN_APP_DEVICE_ID = "device-in-app"
        const val EXTERNAL_DEVICE_ID = "device-external-browser"
        const val CHAT_TURN_LIMIT = 5
        const val SUSPENDED_ID_TOKEN = "google-suspended-user"
    }
}
