package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.social.SocialAccountRegistrar
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant

/**
 * 탈퇴 → 재가입 어뷰징 차단(KNK-1053).
 *
 * 탈퇴가 `social_accounts`를 하드 삭제하던 시절엔 같은 소셜 신원으로 재가입할 때마다 완전히 새 `users` 행이 생겨
 * user_id에 매달린 1회성 혜택(가입 보상 500 · 초대 제출 보상 500)이 통째로 리셋됐다. 이제 탈퇴는 그 행을
 * tombstone(`deleted_at` 기록)으로 보존하고, 재가입은 그 행을 재사용하면서 소진 표식을 새 계정으로 승계한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeSocialLoginConfig::class)
class RejoinAbuseIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var socialAccountRepository: SocialAccountRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var registrar: SocialAccountRegistrar
    @Autowired private lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired private lateinit var redisTemplate: StringRedisTemplate
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    /** 소셜 로그인 후 access 토큰을 돌려준다(sub = idToken 문자열 — FakeSocialLoginConfig 규칙). */
    private fun login(sub: String, deviceId: String? = null): String =
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (deviceId != null) header("X-Manyak-Device-Id", deviceId) }
            .body("""{"idToken":"$sub"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String

    /** 카카오 로그인(같은 가짜 검증기 — sub = idToken 문자열). 두 provider 묶음 시나리오에 쓴다. */
    private fun loginKakao(sub: String) =
        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$sub"}""")
            .exchange()

    private fun withdraw(accessToken: String) {
        restTestClient.delete().uri("/api/v1/users/me")
            .header("Authorization", "Bearer $accessToken")
            .exchange().expectStatus().isNoContent
    }

    private fun userIdOf(sub: String, provider: SocialProvider = SocialProvider.GOOGLE): Long =
        socialAccountRepository.findByProviderAndProviderUserId(provider, sub)!!.userId

    private fun claimAttendance(accessToken: String) =
        restTestClient.post().uri("/api/v1/users/me/credits/attendance")
            .header("Authorization", "Bearer $accessToken")
            .exchange()

    private fun idempotencyKeysOf(userId: Long, reason: CreditReason) =
        creditTransactionRepository.findAll().filter { it.userId == userId && it.reason == reason }
            .map { it.idempotencyKey }

    @Test
    fun `탈퇴 후 같은 소셜 신원으로 재가입하면 새 계정이지만 가입 보상은 다시 주지 않는다`() {
        val token = login("rejoin-reward-sub")
        val firstUserId = userIdOf("rejoin-reward-sub")
        assertThat(creditWalletService.balanceOf(firstUserId)).isEqualTo(500)

        withdraw(token)
        login("rejoin-reward-sub")

        val secondUserId = userIdOf("rejoin-reward-sub")
        // 재가입은 기존 팀 결정대로 새 계정이다(계정 부활 아님 — 탈퇴한 스토리·크레딧이 되살아나면 안 된다).
        assertThat(secondUserId).isNotEqualTo(firstUserId)
        val rejoined = userRepository.findById(secondUserId).orElseThrow()
        assertThat(rejoined.rejoinedAt).isNotNull()
        // 가입 보상은 재가입 계정에 지급되지 않는다(어뷰징 차단의 핵심).
        assertThat(creditWalletService.balanceOf(secondUserId)).isEqualTo(0)
    }

    @Test
    fun `탈퇴해도 social_accounts 행은 tombstone으로 남고 email만 파기된다`() {
        val token = login("tombstone-sub")
        withdraw(token)

        val tombstone = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "tombstone-sub")
        assertThat(tombstone).isNotNull
        assertThat(tombstone!!.deletedAt).isNotNull()
        // 개인정보 파기 요건: 이메일은 지운다. provider_user_id는 제공자 없이 식별 불가한 pseudonymous ID라 남긴다.
        assertThat(tombstone.email).isNull()
        assertThat(tombstone.providerUserId).isEqualTo("tombstone-sub")
    }

    @Test
    fun `tombstone은 로그인 조회에 매칭되지 않아 탈퇴 계정으로 로그인되지 않는다`() {
        val token = login("no-resurrect-sub")
        val deletedUserId = userIdOf("no-resurrect-sub")
        withdraw(token)

        login("no-resurrect-sub")

        // 탈퇴 계정은 DELETED 그대로 남고, 로그인은 새 계정으로 흐른다.
        assertThat(userRepository.findById(deletedUserId).orElseThrow().status).isEqualTo(UserStatus.DELETED)
        assertThat(userIdOf("no-resurrect-sub")).isNotEqualTo(deletedUserId)
        // 소셜 행은 여전히 1개다(새 행 insert 경로가 열리면 유니크 우회가 생긴다).
        assertThat(socialAccountRepository.count()).isEqualTo(1)
    }

    @Test
    fun `재가입 계정은 초대 코드 제출 자격을 승계받아 다시 제출할 수 없다`() {
        val inviter = userRepository.save(User(nickname = "초대자", status = UserStatus.ACTIVE, inviteCode = "REJOIN01"))
        val token = login("rejoin-invite-sub")

        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"REJOIN01"}""")
            .exchange().expectStatus().isOk

        withdraw(token)
        val rejoinToken = login("rejoin-invite-sub")

        // 승계된 inviter_user_id가 평생 1회 소진 표식으로 남아 재제출을 막는다.
        assertThat(userRepository.findById(userIdOf("rejoin-invite-sub")).orElseThrow().inviterUserId)
            .isEqualTo(inviter.id)
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $rejoinToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"REJOIN01"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("INVITE_ALREADY_REDEEMED")
    }

    @Test
    fun `이미 claim된 소셜 행이면 재가입 생성은 중단되고 새 User를 남기지 않는다`() {
        // 재가입 claim은 insert가 아니라 기존 행 UPDATE라 `(provider, provider_user_id)` 유니크가 경합을 막지 못한다.
        // 그대로 두면 동시 요청 둘이 각자 User를 만들고 tombstone은 마지막 쓰기만 가리켜, 먼저 커밋한 쪽은
        // 소셜 행 없는 orphan인데 토큰은 이미 발급된 뒤가 된다. 락 획득 후 재검사로 그 경합을 끊는다.
        //
        // 진짜 스레드 2개는 H2에서 불안정하므로 "상대가 먼저 claim을 커밋한 직후" 상태를 직접 만들어 검증한다.
        val winner = userRepository.save(User(nickname = "먼저claim한계정", status = UserStatus.ACTIVE))
        socialAccountRepository.save(
            SocialAccount(
                userId = winner.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = "claim-race-sub",
                connectedAt = Instant.now(),
                lastLoginAt = Instant.now(),
            ),
        )
        val usersBefore = userRepository.count()

        assertThatThrownBy {
            registrar.createUserAndAccount(
                SocialProvider.GOOGLE,
                SocialUserInfo(providerUserId = "claim-race-sub"),
                Instant.now(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // 바깥(SocialLoginService)의 기존 복구 경로가 재조회로 winner 계정을 재사용하도록, 여기선 아무것도 남기지 않는다.
        assertThat(userRepository.count()).isEqualTo(usersBefore)
        assertThat(socialAccountRepository.count()).isEqualTo(1)
        assertThat(userIdOf("claim-race-sub")).isEqualTo(winner.id)
    }

    @Test
    fun `재가입해도 같은 날 출석 보상을 다시 받지 못한다`() {
        // 출석 멱등 키가 user_id 기준이면 재가입 1회당 250을 하루에도 무제한 반복 수령할 수 있었다.
        // 키를 보상 신원(최초 계정)으로 묶어 막는다.
        val token = login("attendance-rejoin-sub")
        claimAttendance(token).expectStatus().isOk.expectBody().jsonPath("$.rewarded").isEqualTo(true)

        withdraw(token)
        val rejoinToken = login("attendance-rejoin-sub")

        claimAttendance(rejoinToken)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.rewarded").isEqualTo(false)
            .jsonPath("$.amount").isEqualTo(0)
        // 원장에도 출석 행이 늘지 않는다(최초 계정 1건뿐).
        val rejoinedUserId = userIdOf("attendance-rejoin-sub")
        assertThat(idempotencyKeysOf(rejoinedUserId, CreditReason.ATTENDANCE_REWARD)).isEmpty()
    }

    @Test
    fun `최초 계정이 오늘 출석하지 않았으면 재가입 계정은 출석 보상을 받는다`() {
        // 과소 차단 회귀 방지: "재가입이면 무조건 금지"가 아니라 "이 신원이 오늘 받았는가"로 판정한다.
        val token = login("attendance-unused-sub")
        withdraw(token)

        val rejoinToken = login("attendance-unused-sub")

        claimAttendance(rejoinToken)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.rewarded").isEqualTo(true)
            .jsonPath("$.amount").isEqualTo(250)
    }

    @Test
    fun `최초 계정이 가입 보상을 못 받았으면 재가입 계정이 받는다`() {
        // 루트 키 방식이 blanket skip보다 정확하다는 고정: 최초 계정에 signup 원장 행이 없는 상태
        // (생성은 커밋됐는데 보상 적립 전에 크래시)를 tombstone으로 만들어 두고 재가입시킨다.
        val crashed = userRepository.save(User(nickname = "보상유실탈퇴자", status = UserStatus.DELETED))
        socialAccountRepository.save(
            SocialAccount(
                userId = crashed.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = "signup-lost-sub",
                deletedAt = Instant.now(),
            ),
        )
        assertThat(creditWalletService.balanceOf(crashed.id)).isEqualTo(0)

        login("signup-lost-sub")

        val rejoinedUserId = userIdOf("signup-lost-sub")
        assertThat(creditWalletService.balanceOf(rejoinedUserId)).isEqualTo(500)
        // 키는 새 user_id가 아니라 최초 계정(보상 신원)으로 찍힌다.
        assertThat(idempotencyKeysOf(rejoinedUserId, CreditReason.SIGNUP_REWARD))
            .containsExactly("signup:${crashed.id}")
    }

    @Test
    fun `재가입 계정은 디바이스 헤더를 보내도 회원 무료 체험을 부여받지 못한다`() {
        val token = login("trial-rejoin-sub")
        withdraw(token)

        // 사용 이력 없는 새 디바이스로 재가입한다. 그대로 시드하면 게스트 카운터가 비어 있어 full 체험이 남는다
        // (무료 스토리 1편 + 채팅 5턴을 탈퇴·재가입 반복으로 무한 수령). 재가입은 디바이스를 무시해야 한다.
        login("trial-rejoin-sub", deviceId = "brand-new-device")

        // deviceId 미증명 경로로 흘러 한도값으로 시드된다(= 소진 상태, 무료 체험 미부여).
        val rejoinedUserId = userIdOf("trial-rejoin-sub")
        assertThat(redisTemplate.opsForValue().get("member_trial:$rejoinedUserId:story_creation")).isEqualTo("1")
        assertThat(redisTemplate.opsForValue().get("member_trial:$rejoinedUserId:chat_turn")).isEqualTo("5")
    }

    @Test
    fun `정지된 회원이 탈퇴 후 재가입하면 새 계정도 정지 상태를 승계한다`() {
        val token = login("suspended-rejoin-sub")
        val suspended = userRepository.findById(userIdOf("suspended-rejoin-sub")).orElseThrow()
        suspended.status = UserStatus.SUSPENDED
        userRepository.save(suspended)

        // 탈퇴 자체는 계속 허용한다(앱 심사 요건 — 정지 회원이라고 계정 삭제를 막을 수 없다).
        withdraw(token)
        val rejoinToken = login("suspended-rejoin-sub")

        val rejoinedUserId = userIdOf("suspended-rejoin-sub")
        assertThat(userRepository.findById(rejoinedUserId).orElseThrow().status).isEqualTo(UserStatus.SUSPENDED)
        // 제재가 실제로 걸린다 — 정지 계정의 쓰기·소모 경로는 403이다(§4-5 B20).
        restTestClient.get().uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer $rejoinToken")
            .exchange().expectStatus().isForbidden
    }

    @Test
    fun `순수 신규 가입의 멱등 키 문자열은 종전과 같다`() {
        // 회귀(원장 호환): reward_identity_user_id 가 NULL 인 계정은 signup:{id}·attendance:{id}:{날짜} 그대로다.
        val token = login("legacy-key-sub")
        claimAttendance(token).expectStatus().isOk

        val userId = userIdOf("legacy-key-sub")
        assertThat(userRepository.findById(userId).orElseThrow().rewardIdentityUserId).isNull()
        assertThat(idempotencyKeysOf(userId, CreditReason.SIGNUP_REWARD)).containsExactly("signup:$userId")
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        assertThat(idempotencyKeysOf(userId, CreditReason.ATTENDANCE_REWARD))
            .containsExactly("attendance:$userId:$today")
    }

    @Test
    fun `재가입 계정은 게스트 이관 시도 횟수를 승계하고 이관 잠금은 다시 열린다`() {
        // 이관 시도 상한(B19, KNK-500)은 소유 상태 열거 오라클을 제한하는 **사람 단위 예산**이라 승계한다.
        // 리셋되면 탈퇴·재가입 반복으로 사실상 무제한 열거가 된다.
        val token = login("migration-rejoin-sub")
        val first = userRepository.findById(userIdOf("migration-rejoin-sub")).orElseThrow()
        first.migrationAttempts = 4
        first.migratedAt = Instant.now()
        userRepository.save(first)

        withdraw(token)
        login("migration-rejoin-sub")

        val rejoined = userRepository.findById(userIdOf("migration-rejoin-sub")).orElseThrow()
        assertThat(rejoined.migrationAttempts).isEqualTo(4)
        // migrated_at은 "이 계정이 게스트 콘텐츠를 가져갔다"는 계정 단위 잠금이라 새 계정에는 다시 열어 준다
        // (재가입자가 새로 만든 게스트 콘텐츠를 가져갈 길을 막지 않는다. 이관은 크레딧을 주지 않아 파밍 실익도 없다).
        assertThat(rejoined.migratedAt).isNull()
    }

    /** 구글·카카오를 연동한 계정을 만든다(재인증 → 링크 코드 → 연동). 반환값은 그 계정의 access 토큰. */
    private fun loginWithBothProviders(googleSub: String, kakaoSub: String): String {
        val access = login(googleSub)
        val linkCode = restTestClient.post().uri("/api/v1/auth/links/reauth")
            .header("Authorization", "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"provider":"GOOGLE","idToken":"$googleSub"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(Map::class.java).returnResult().responseBody!!["linkCode"] as String
        restTestClient.post().uri("/api/v1/auth/links/kakao")
            .header("Authorization", "Bearer $access")
            .header("X-Manyak-Link-Code", linkCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$kakaoSub"}""")
            .exchange().expectStatus().isCreated
        return access
    }

    @Test
    fun `두 provider를 연동한 계정이 탈퇴하면 재가입이 tombstone 묶음 전체를 가져온다`() {
        // Codex P2: 로그인한 provider의 행 하나만 옮기면 나머지가 옛 소유자를 계속 가리켜,
        // 재가입 이후 쌓인 표식이 형제에게 보이지 않는다.
        val access = loginWithBothProviders("bundle-google-sub", "bundle-kakao-sub")
        withdraw(access)

        login("bundle-google-sub")

        val rejoinedUserId = userIdOf("bundle-google-sub")
        val kakao = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "bundle-kakao-sub")!!
        assertThat(kakao.userId).isEqualTo(rejoinedUserId)
        assertThat(kakao.deletedAt).isNull()
        // 로그인하지 않은 형제 행은 lastLoginAt을 찍지 않는다(연동만 복원).
        assertThat(kakao.lastLoginAt).isNull()
        assertThat(socialAccountRepository.count()).isEqualTo(2)
    }

    @Test
    fun `묶음을 가져온 뒤 다른 provider로 로그인해도 새 계정이 생기지 않는다`() {
        val access = loginWithBothProviders("sibling-google-sub", "sibling-kakao-sub")
        withdraw(access)
        login("sibling-google-sub")
        val rejoinedUserId = userIdOf("sibling-google-sub")
        val usersBefore = userRepository.count()

        // 카카오 행이 tombstone이 아니라 살아 있는 연동이라 findExistingUser가 같은 계정을 돌려준다.
        loginKakao("sibling-kakao-sub")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isNewUser").isEqualTo(false)

        assertThat(userRepository.count()).isEqualTo(usersBefore)
        assertThat(userIdOf("sibling-kakao-sub", SocialProvider.KAKAO)).isEqualTo(rejoinedUserId)
    }

    @Test
    fun `재가입 계정이 초대 코드를 소진하면 형제 provider 로그인도 자격을 얻지 못한다`() {
        // 표식의 출처가 하나로 모였는지 확인하는 가드 — 형제 행이 옛 소유자에 남아 있으면 표식 없는 계정이 생긴다.
        val inviter = userRepository.save(User(nickname = "초대자", status = UserStatus.ACTIVE, inviteCode = "BUNDLE01"))
        val access = loginWithBothProviders("mark-google-sub", "mark-kakao-sub")
        withdraw(access)
        val rejoinToken = login("mark-google-sub")

        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $rejoinToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"BUNDLE01"}""")
            .exchange().expectStatus().isOk

        val kakaoToken = loginKakao("mark-kakao-sub")
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String

        assertThat(userIdOf("mark-kakao-sub", SocialProvider.KAKAO)).isEqualTo(userIdOf("mark-google-sub"))
        assertThat(userRepository.findById(userIdOf("mark-kakao-sub", SocialProvider.KAKAO)).orElseThrow().inviterUserId)
            .isEqualTo(inviter.id)
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $kakaoToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"BUNDLE01"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("INVITE_ALREADY_REDEEMED")
    }

    @Test
    fun `초대자가 상한을 채운 뒤 재가입해도 월 상한이 리셋되지 않는다`() {
        // Codex 재리뷰 P1: 초대자 몫 키·집계가 user_id 기준이면, 크레딧을 다 쓰고 탈퇴·재가입하는 것만으로
        // 월 상한이 0으로 초기화된다(지갑 소멸이 페널티가 아니다). 키를 보상 신원에 묶어 신원 단위로 이어 센다.
        val token = login("cap-rejoin-sub")
        val inviterId = userIdOf("cap-rejoin-sub")
        // 이번 달 초대자 역할 보상을 상한(10)까지 채운다 — 실제 키 모양 invite:{초대자}:{피초대자}:{초대자}.
        repeat(10) { i ->
            creditWalletService.reward(inviterId, 500, CreditReason.INVITE_REWARD, "invite:$inviterId:${900_000L + i}:$inviterId")
        }

        withdraw(token)
        val rejoinToken = login("cap-rejoin-sub")

        // 재가입 계정의 진행 표시가 이전 계정 수령분을 그대로 이어받는다(상한 도달 상태).
        restTestClient.get().uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer $rejoinToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.monthlyRewardCount").isEqualTo(10)
            .jsonPath("$.monthlyRewardLimit").isEqualTo(10)

        // 실제 적립도 계속 스킵된다 — 재가입 계정이 새 코드로 초대해도 초대자 몫은 안 준다(제출자만 받는다).
        val inviteCode = restTestClient.get().uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer $rejoinToken")
            .exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["inviteCode"] as String
        val redeemerToken = login("cap-redeemer-sub")
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $redeemerToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"$inviteCode"}""")
            .exchange().expectStatus().isOk

        val rejoinedInviterId = userIdOf("cap-rejoin-sub")
        // 재가입 계정 명의로는 초대자 몫 원장이 한 건도 생기지 않는다(상한 스킵).
        assertThat(idempotencyKeysOf(rejoinedInviterId, CreditReason.INVITE_REWARD)).isEmpty()
    }

    @Test
    fun `순수 신규 가입의 초대 멱등 키 문자열은 종전과 같다`() {
        // 회귀(원장 호환): reward_identity_user_id 가 NULL 이면 키가 invite:{초대자id}:{제출자id}:{수혜자id} 그대로다.
        val inviterToken = login("legacy-invite-inviter-sub")
        val inviteCode = restTestClient.get().uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer $inviterToken")
            .exchange().expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["inviteCode"] as String
        val redeemerToken = login("legacy-invite-redeemer-sub")

        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $redeemerToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"$inviteCode"}""")
            .exchange().expectStatus().isOk

        val inviterId = userIdOf("legacy-invite-inviter-sub")
        val redeemerId = userIdOf("legacy-invite-redeemer-sub")
        assertThat(idempotencyKeysOf(inviterId, CreditReason.INVITE_REWARD))
            .containsExactly("invite:$inviterId:$redeemerId:$inviterId")
        assertThat(idempotencyKeysOf(redeemerId, CreditReason.INVITE_REWARD))
            .containsExactly("invite:$inviterId:$redeemerId:$redeemerId")
    }

    @Test
    fun `tombstone 소셜 행에는 로그인 시각 갱신이 아무것도 바꾸지 않는다`() {
        // Codex 재리뷰 P2: 로그인이 엔티티 dirty checking으로 lastLoginAt을 쓰면 UPDATE가 전 컬럼을 덮어,
        // 동시에 커밋된 탈퇴의 tombstone·이메일 파기를 되돌린다. 조건부 단일 컬럼 갱신이라 tombstone은 불변이어야 한다.
        val token = login("touch-tombstone-sub")
        withdraw(token)
        val tombstone = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "touch-tombstone-sub")!!
        val ownerId = tombstone.userId
        val deletedAt = tombstone.deletedAt

        assertThat(socialAccountRepository.touchLastLoginAt(tombstone.id, Instant.now())).isEqualTo(0)

        val unchanged = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "touch-tombstone-sub")!!
        assertThat(unchanged.deletedAt).isEqualTo(deletedAt)
        assertThat(unchanged.email).isNull()
        assertThat(unchanged.userId).isEqualTo(ownerId)
        assertThat(unchanged.lastLoginAt).isEqualTo(tombstone.lastLoginAt)
    }

    @Test
    fun `탈퇴가 로그인 조회와 겹쳐도 재가입 계정으로 로그인되고 좀비 세션이 생기지 않는다`() {
        // Codex 3차 리뷰 P2: findExistingUser가 살아 있는 행을 잡은 뒤 탈퇴가 먼저 커밋되면 lastLoginAt 갱신이
        // 0행이 된다. 그때 이전 User를 돌려주면 DELETED 계정으로 토큰이 나가 "로그인 200 → 이후 전부 401"이 된다.
        // 경합을 스레드로 만들지 않고, 탈퇴가 이미 커밋된 상태에서 같은 신원으로 로그인해 재가입 경로가 도는지로 고정한다.
        val token = login("raced-withdrawal-sub")
        val deletedUserId = userIdOf("raced-withdrawal-sub")
        withdraw(token)

        val rejoinToken = login("raced-withdrawal-sub")

        // 새 계정이 생기고 토큰도 그 계정 것이다(탈퇴 계정 토큰이 아니다).
        val rejoinedUserId = userIdOf("raced-withdrawal-sub")
        assertThat(rejoinedUserId).isNotEqualTo(deletedUserId)
        assertThat(userRepository.findById(deletedUserId).orElseThrow().status).isEqualTo(UserStatus.DELETED)
        // 발급된 토큰이 실제로 쓸 수 있어야 한다 — 좀비 세션이면 여기서 401이 난다.
        restTestClient.get().uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $rejoinToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("ACTIVE")
    }

    @Test
    fun `탈퇴는 이미 커밋된 초대 소진 표식을 되돌리지 않는다`() {
        // Codex 4차 리뷰 P1: 탈퇴가 사용자 행을 잠그지 않고 읽으면, 읽은 뒤 커밋된 redeem의 inviter_user_id가
        // 탈퇴의 전 컬럼 UPDATE에 NULL로 덮인다. 그러면 그 tombstone으로 재가입한 계정이 초대 보상을 다시 받는다.
        // 진짜 경합은 스레드로 만들지 않고, "제출 커밋 → 탈퇴 → 재가입"에서 표식이 살아남는지로 고정한다.
        val inviter = userRepository.save(User(nickname = "초대자", status = UserStatus.ACTIVE, inviteCode = "LOCKED01"))
        val token = login("withdraw-lock-sub")
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"LOCKED01"}""")
            .exchange().expectStatus().isOk
        val redeemerId = userIdOf("withdraw-lock-sub")

        withdraw(token)

        // 탈퇴한 계정에도 소진 표식이 남아 있어야 재가입이 그걸 승계한다.
        assertThat(userRepository.findById(redeemerId).orElseThrow().inviterUserId).isEqualTo(inviter.id)
        val rejoinToken = login("withdraw-lock-sub")
        assertThat(userRepository.findById(userIdOf("withdraw-lock-sub")).orElseThrow().inviterUserId)
            .isEqualTo(inviter.id)
        restTestClient.post().uri("/api/v1/users/me/invite/redeem")
            .header("Authorization", "Bearer $rejoinToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"LOCKED01"}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("INVITE_ALREADY_REDEEMED")
    }
}
