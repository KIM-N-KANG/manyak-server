package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.server.ResponseStatusException

/**
 * `handoffCode`를 실은 Google 로그인이 시드와 이관을 함께 수행하는지 검증한다(스펙 §4-3-5 로그인 핸드오프).
 *
 * 별도 consume 엔드포인트는 없다 — 로그인 호출이 소비를 겸한다. 시드를 로그인 뒤로 미루면
 * 디바이스 헤더 없는 첫 로그인이 소진 시드를 비가역으로 확정하기 때문이다(§4-3-7).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHandoffConsumptionIntegrationTests {

    @TestConfiguration
    class FakeGoogleConfig {
        @Bean
        @Primary
        fun fakeGoogleIdTokenVerifier(): GoogleIdTokenVerifier =
            GoogleIdTokenVerifier { idToken ->
                if (idToken == "invalid") {
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
                }
                SocialUserInfo(
                    providerUserId = idToken,
                    email = "user@example.com",
                    name = "테스터",
                    picture = null,
                )
            }

        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var storyRepository: StoryRepository
    @Autowired private lateinit var storyChatRepository: StoryChatRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var socialAccountRepository: SocialAccountRepository
    @Autowired private lateinit var guestTrialLimitService: GuestTrialLimitService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
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
