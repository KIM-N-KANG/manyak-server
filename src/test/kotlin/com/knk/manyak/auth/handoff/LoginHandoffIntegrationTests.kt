package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Duration
import java.util.UUID

/**
 * 로그인 핸드오프 통합 검증(스펙 §4-3-5): HTTP 계약과 Redis 저장·소비 규칙.
 *
 * **커스텀 `@TestConfiguration`을 두지 않는다.** 테스트 클래스마다 설정이 다르면 Spring 컨텍스트가 하나씩 더
 * 뜨는데, 이 스위트는 이미 컨텍스트 캐시 상한(32) 언저리라 하나만 늘어도 축출·재기동이 반복돼 CI에서
 * 다른 테스트의 HTTP 호출이 타임아웃난다(이 PR에서 실제로 관측). 그래서 가짜 Google 검증기가 필요한
 * 로그인 연동 검증은 Spring 없이 도는 [com.knk.manyak.auth.social.GoogleLoginServiceTest]가 맡고,
 * 여기서는 소비를 [LoginHandoffService]에 직접 요청해 같은 규칙을 검증한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHandoffIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var loginHandoffService: LoginHandoffService
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var guestTrialLimitService: GuestTrialLimitService
    @Autowired private lateinit var redisTemplate: StringRedisTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    // ---- 생성 ----

    @Test
    fun `핸드오프를 생성하면 코드와 분석용 id, 만료 시각을 돌려준다`() {
        val response = createHandoff(storyIds = listOf(UUID.randomUUID().toString()))

        assertThat(response.handoffCode).isNotBlank()
        assertThat(response.handoffId).isNotBlank()
        // 코드와 분석용 id는 별개 값이다(분석 이벤트에는 handoffId만 싣는다).
        assertThat(response.handoffCode).isNotEqualTo(response.handoffId)
        assertThat(response.expiresAt).isNotBlank()
    }

    @Test
    fun `디바이스 헤더 없이 생성하면 400이다`() {
        restTestClient.post().uri(HANDOFFS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody())
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `앱 밖으로 나가는 복귀 경로는 400으로 거부한다`() {
        listOf("//evil.com", "https://evil.com", "/\\evil.com", "evil").forEach { path ->
            postHandoff(createBody(callbackPath = path)).expectStatus().isBadRequest
        }
    }

    @Test
    fun `지나치게 긴 복귀 경로는 400이다`() {
        // 인증 없는 엔드포인트라 상한이 없으면 거대한 상대 경로가 그대로 Redis에 보관된다(메모리 증폭).
        postHandoff(createBody(callbackPath = "/" + "a".repeat(2048))).expectStatus().isBadRequest
    }

    @Test
    fun `알 수 없는 출처 앱은 400으로 거부한다`() {
        postHandoff(createBody(sourceApp = "facebook")).expectStatus().isBadRequest
    }

    @Test
    fun `UUID 형식이 아닌 ID가 섞이면 400이다`() {
        // 미인증 엔드포인트라 임의 길이 문자열을 그대로 보관하면 Redis 메모리 증폭 통로가 된다.
        postHandoff(createBody(storyIds = listOf("not-a-uuid"))).expectStatus().isBadRequest
    }

    @Test
    fun `ID 배열이 상한을 넘으면 400이다`() {
        val tooMany = (1..101).map { UUID.randomUUID().toString() }

        postHandoff(createBody(storyIds = tooMany)).expectStatus().isBadRequest
    }

    // ---- 확인·상태 조회 ----

    @Test
    fun `확인 호출은 옮길 건수와 복귀 경로를 돌려주고 상태를 LANDED로 바꾼다`() {
        val created = createHandoff(
            storyIds = listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            chatIds = listOf(UUID.randomUUID().toString()),
        )

        confirm(created.handoffCode)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.storyCount").isEqualTo(2)
            .jsonPath("$.chatCount").isEqualTo(1)
            .jsonPath("$.callbackPath").isEqualTo(CALLBACK_PATH)

        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("LANDED")
    }

    @Test
    fun `생성 직후 상태는 PENDING이다`() {
        val status = fetchStatus(createHandoff().handoffCode)

        assertThat(status.status).isEqualTo("PENDING")
        assertThat(status.migratedStoryIds).isEmpty()
        assertThat(status.migratedChatIds).isEmpty()
    }

    @Test
    fun `없는 코드는 확인도 상태 조회도 404다`() {
        // 존재하지 않는 코드와 만료된 코드를 구분하지 않는다(열거 오라클 방지).
        confirm("존재하지-않는-코드").expectStatus().isNotFound
        restTestClient.get().uri(STATUS_PATH)
            .header(HEADER_HANDOFF_CODE, "존재하지-않는-코드")
            .exchange()
            .expectStatus().isNotFound
    }

    // ---- 소비(로그인 호출이 겸하는 동작을 서비스에 직접 요청해 검증한다) ----

    @Test
    fun `소비하면 보관된 게스트 데이터가 계정으로 이관된다`() {
        val story = saveStory(ownerId = null)
        val chat = saveChat(ownerId = null)
        val owner = saveUser()
        val created = createHandoff(
            storyIds = listOf(story.publicId.toString()),
            chatIds = listOf(chat.publicId.toString()),
        )

        consume(created.handoffCode, owner.id)

        assertThat(storyRepository.findById(story.id).get().userId).isEqualTo(owner.id)
        assertThat(storyChatRepository.findById(chat.id).get().userId).isEqualTo(owner.id)
        val status = fetchStatus(created.handoffCode)
        assertThat(status.status).isEqualTo("MIGRATED")
        assertThat(status.migratedStoryIds).containsExactly(story.publicId.toString())
        assertThat(status.migratedChatIds).containsExactly(chat.publicId.toString())
    }

    @Test
    fun `이미 소비한 코드를 다시 소비해도 결과가 유지된다`() {
        val story = saveStory(ownerId = null)
        val owner = saveUser()
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))

        consume(created.handoffCode, owner.id)
        consume(created.handoffCode, owner.id)

        assertThat(fetchStatus(created.handoffCode).migratedStoryIds)
            .containsExactly(story.publicId.toString())
    }

    @Test
    fun `뒤늦게 도착한 중복 소비가 먼저 이관한 결과를 덮어쓰지 않는다`() {
        // 같은 코드를 실은 로그인이 동시에 둘 들어오면 둘 다 PENDING 스냅샷으로 상태 가드를 통과한다.
        // 먼저 이관한 쪽이 계정을 잠그므로 나중 쪽은 migrationClosed(빈 목록)를 받는데, 그 결과로 덮어쓰면
        // status가 이관된 ID를 잃어 인앱이 로컬 정리를 못 한다(그 데이터는 이미 회원 소유라 게스트 조회는 403).
        val story = saveStory(ownerId = null)
        val owner = saveUser()
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))
        val staleSnapshot = loginHandoffService.find(created.handoffCode)!!

        consume(created.handoffCode, owner.id)
        loginHandoffService.consume(created.handoffCode, staleSnapshot, owner.id)

        val status = fetchStatus(created.handoffCode)
        assertThat(status.status).isEqualTo("MIGRATED")
        assertThat(status.migratedStoryIds).containsExactly(story.publicId.toString())
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
        consume(created.handoffCode, saveUser().id)
        // TTL도 함께 되돌린다. 남은 TTL이 없으면 갱신 스크립트가 만료로 보고 쓰지 않아 가드를 검증하지 못한다.
        redisTemplate.opsForValue()
            .set(key, storedJson(key).replace("\"MIGRATED\"", "\"PENDING\""), Duration.ofMinutes(30))

        confirm(created.handoffCode).expectStatus().isOk

        assertThat(fetchStatus(created.handoffCode).status)
            .`as`("소비권이 나갔으므로 확인 호출은 상태를 전이시키지 않아야 한다")
            .isEqualTo("PENDING")
    }

    @Test
    fun `소비하고 나면 원본 디바이스 ID 원문은 보관하지 않는다`() {
        // 소비 결과는 24시간 연장 보관하지만, 디바이스 ID 원문은 핸드오프 수명 동안만 남아야 한다(스펙 §4-3-5).
        val created = createHandoff()

        consume(created.handoffCode, saveUser().id)

        assertThat(loginHandoffService.find(created.handoffCode)?.deviceId)
            .`as`("소비 후에는 디바이스 ID 원문이 남지 않아야 한다")
            .isNullOrEmpty()
    }

    @Test
    fun `옮길 항목이 없는 핸드오프는 이관 시도 상한을 쓰지 않는다`() {
        // 이관 서비스는 배열을 보기 전에 시도 횟수를 올린다. 디바이스만 실은 인앱 로그인이 그 상한(5회)을
        // 갉아먹으면, 나중에 진짜 옮길 데이터를 실은 핸드오프가 평가도 못 받고 MIGRATION_CLOSED가 된다.
        val owner = saveUser()
        val created = createHandoff()

        consume(created.handoffCode, owner.id)

        assertThat(userRepository.findById(owner.id).get().migrationAttempts).isZero()
        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("MIGRATED")
    }

    @Test
    fun `이관이 실패하면 핸드오프는 미소비로 남아 재시도할 수 있다`() {
        // 정지 계정의 이관은 403이다(§4-5 B20). 소비되지 않고 남아야 만료 전까지 재시도할 수 있다.
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))
        val story = saveStory(ownerId = null)
        val created = createHandoff(storyIds = listOf(story.publicId.toString()))

        assertThatThrownBy { consume(created.handoffCode, suspended.id) }
            .hasMessageContaining("정지된 계정")

        assertThat(storyRepository.findById(story.id).get().userId).isNull()
        assertThat(fetchStatus(created.handoffCode).status).isEqualTo("PENDING")
    }

    @Test
    fun `핸드오프에 보관한 디바이스의 게스트 사용량이 회원 체험으로 이어진다`() {
        // 인앱 디바이스가 채팅 체험을 모두 소진한 상태에서 그 디바이스로 시드하면 회원 잔여도 0이어야 한다.
        repeat(CHAT_TURN_LIMIT) {
            assertThat(guestTrialLimitService.reserve(IN_APP_DEVICE_ID, GuestTrialLimitService.Counter.CHAT_TURN))
                .isTrue()
        }
        val owner = saveUser()
        val handoff = loginHandoffService.find(createHandoff(deviceId = IN_APP_DEVICE_ID).handoffCode)!!

        assertThat(guestTrialLimitService.snapshotTrialAtSignup(owner.id, handoff.deviceId)).isTrue()

        assertThat(guestTrialLimitService.reserveMember(owner.id, GuestTrialLimitService.Counter.CHAT_TURN))
            .`as`("인앱에서 소진한 체험이 회원 계정으로 이어져야 한다")
            .isFalse()
    }

    // ---- 픽스처 ----

    private fun saveUser(): User = userRepository.save(User(nickname = "회원", status = UserStatus.ACTIVE))

    private fun saveStory(ownerId: Long?): Story = storyRepository.save(Story(userId = ownerId, title = "제목"))

    private fun saveChat(ownerId: Long?): StoryChat {
        val story = storyRepository.save(Story(title = "채팅용 스토리"))
        return storyChatRepository.save(StoryChat(userId = ownerId, storyId = story.id))
    }

    private fun consume(code: String, userId: Long) =
        loginHandoffService.consume(code, loginHandoffService.find(code)!!, userId)

    private fun handoffKeys(): Set<String> = redisTemplate.keys("login_handoff:*").orEmpty()

    private fun storedJson(key: String): String = redisTemplate.opsForValue().get(key)!!

    private fun createBody(
        storyIds: List<String> = emptyList(),
        chatIds: List<String> = emptyList(),
        callbackPath: String = CALLBACK_PATH,
        sourceApp: String = "instagram",
    ): Map<String, Any> = mapOf(
        "storyIds" to storyIds,
        "chatIds" to chatIds,
        "callbackPath" to callbackPath,
        "sourceApp" to sourceApp,
    )

    private fun postHandoff(body: Map<String, Any>, deviceId: String = DEVICE_ID) =
        restTestClient.post().uri(HANDOFFS_PATH)
            .header(RequestCorrelationFilter.HEADER_DEVICE_ID, deviceId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun createHandoff(
        storyIds: List<String> = emptyList(),
        chatIds: List<String> = emptyList(),
        deviceId: String = DEVICE_ID,
    ): CreatedHandoff = postHandoff(createBody(storyIds = storyIds, chatIds = chatIds), deviceId)
        .expectStatus().isCreated
        .expectBody(CreatedHandoff::class.java)
        .returnResult()
        .responseBody!!

    private fun confirm(code: String) = restTestClient.get().uri(HANDOFFS_PATH)
        .header(HEADER_HANDOFF_CODE, code)
        .exchange()

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
        const val CALLBACK_PATH = "/chats/3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        const val DEVICE_ID = "device-in-app-instagram"
        const val IN_APP_DEVICE_ID = "device-in-app-trial"
        const val CHAT_TURN_LIMIT = 5
    }
}
