package com.knk.manyak.auth.controller

import com.knk.manyak.auth.consent.ConsentTokenStore
import com.knk.manyak.auth.consent.SocialConsentService
import com.knk.manyak.auth.handoff.LoginHandoffService
import com.knk.manyak.auth.handoff.LoginHandoffCreateRequest
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.user.consent.UserConsentRequest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doCallRealMethod
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@Import(FakeSocialLoginConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["manyak.auth.consent-gate.enabled=true"])
class SocialConsentIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var socials: SocialAccountRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @MockitoSpyBean private lateinit var analytics: com.knk.manyak.global.observability.analytics.ServerAnalytics
    @MockitoSpyBean private lateinit var codes: ConsentTokenStore
    @Autowired private lateinit var service: SocialConsentService
    @Autowired private lateinit var handoffs: LoginHandoffService
    @Autowired private lateinit var consentService: com.knk.manyak.user.consent.UserConsentService
    @Autowired private lateinit var trials: GuestTrialLimitService
    private val mapper = ObjectMapper()

    @BeforeEach fun clean() {
        cleaner.cleanAll()
        redis.keys("social_consent:*").takeIf { it.isNotEmpty() }?.let(redis::delete)
    }

    private fun start(sub: String = "new-sub", provider: String = "google"): JsonNode = json(client.post()
        .uri("/api/v1/auth/social/$provider").header("Authorization", "Bearer stale")
        .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"$sub"}""").exchange().expectStatus().isOk)
    private fun complete(code: String, body: String = ALL) = client.post().uri("/api/v1/auth/social/complete")
        .header("X-Manyak-Consent-Token", code).header("Authorization", "Bearer stale")
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode = mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
    private fun token(sub: String): JsonNode = json(client.post().uri("/api/v1/auth/login/google")
        .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"$sub"}""").exchange().expectStatus().isOk)

    @Test fun `신규는 동의 전 어떤 회원 데이터도 만들지 않고 완료 후 가입한다`() {
        val pending = start()
        assertThat(pending["status"].asText()).isEqualTo("CONSENT_REQUIRED")
        assertThat(pending["isNewUser"].asBoolean()).isTrue()
        assertThat(pending.hasNonNull("token")).isFalse()
        assertThat(pending["consents"]["terms"]["requiredVersion"].asText()).isEqualTo("v1.4")
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations.filter { it.method.name == "socialLoginSucceeded" }).isEmpty()
        assertThat(count("users")).isZero()
        assertThat(count("social_accounts")).isZero()
        assertThat(count("user_consents")).isZero()
        assertThat(count("credit_transactions")).isZero()
        val code = pending["consentToken"].asText()
        val tokens = json(complete(code).expectStatus().isOk)
        assertThat(tokens["isNewUser"].asBoolean()).isTrue()
        assertThat(tokens["accessToken"].asText()).isNotBlank()
        assertThat(count("users")).isEqualTo(1)
        assertThat(count("user_consents")).isEqualTo(3)
        complete(code).expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("CONSENT_TOKEN_INVALID")
        assertThat(start()["status"].asText()).isEqualTo("COMPLETED")
        assertThat(count("credit_transactions")).isEqualTo(1)
    }

    @Test fun `미동의 기존 회원은 시각을 갱신하지 않고 같은 계정에 재동의한다`() {
        token("existing")
        jdbc.update("UPDATE social_accounts SET last_login_at = TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00'")
        val before = jdbc.queryForObject("SELECT last_login_at FROM social_accounts", java.time.OffsetDateTime::class.java)
        val userId = users.findAll().single().id
        val pending = start("existing")
        assertThat(pending["isNewUser"].asBoolean()).isFalse()
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM social_accounts", java.time.OffsetDateTime::class.java)).isEqualTo(before)
        complete(pending["consentToken"].asText()).expectStatus().isOk.expectBody().jsonPath("$.isNewUser").isEqualTo(false)
        assertThat(users.findAll().single().id).isEqualTo(userId)
        jdbc.update("DELETE FROM user_consents WHERE doc_type = 'TERMS'")
        val revised = start("existing")
        assertThat(revised["consents"]["privacy"]["needsConsent"].asBoolean()).isFalse()
        complete(revised["consentToken"].asText(), """{"terms":"v1.4"}""").expectStatus().isOk
        assertThat(count("users")).isEqualTo(1)
        assertThat(count("user_consents")).isEqualTo(3)
    }

    @Test fun `버전 검증이 누락 검사보다 먼저이며 오류는 코드를 소비하지 않는다`() {
        val code = start()["consentToken"].asText()
        complete(code, """{"terms":"old"}""").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("CONSENT_VERSION_MISMATCH")
        complete(code, """{"terms":"v1.4"}""").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("CONSENT_REQUIRED_MISSING")
        assertThat(count("users")).isZero()
        complete(code).expectStatus().isOk
    }

    @Test fun `코드 누락 만료와 위조는 401이며 본문보다 먼저 확인한다`() {
        val code = start()["consentToken"].asText()
        redis.keys("social_consent:*").forEach { redis.expire(it, java.time.Duration.ZERO) }
        complete(code, """{"terms":"old"}""").expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("CONSENT_TOKEN_INVALID")
        complete("unknown").expectStatus().isUnauthorized
        client.post().uri("/api/v1/auth/social/complete").contentType(MediaType.APPLICATION_JSON).body(ALL)
            .exchange().expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("CONSENT_TOKEN_INVALID")
        assertThat(count("users")).isZero()
    }

    @Test fun `정지 회원은 새 완료 경로만 허용하고 기존 동의 API는 거부한다`() {
        token("suspended")
        val user = users.findAll().single().apply { status = UserStatus.SUSPENDED }
        users.saveAndFlush(user)
        val result = json(complete(start("suspended")["consentToken"].asText()).expectStatus().isOk)
        client.post().uri("/api/v1/users/me/consents").header("Authorization", "Bearer ${result["accessToken"].asText()}")
            .contentType(MediaType.APPLICATION_JSON).body(ALL).exchange().expectStatus().isForbidden
        assertThat(start("suspended")["status"].asText()).isEqualTo("COMPLETED")
    }

    @Test fun `재가입은 동의 전 tombstone을 유지하고 완료에서 정지와 보상 신원을 승계한다`() {
        val tokens = token("rejoin")
        val old = users.findAll().single()
        old.status = UserStatus.SUSPENDED
        users.saveAndFlush(old)
        client.delete().uri("/api/v1/users/me").header("Authorization", "Bearer ${tokens["accessToken"].asText()}")
            .exchange().expectStatus().isNoContent
        val pending = start("rejoin")
        assertThat(pending["isNewUser"].asBoolean()).isTrue()
        assertThat(count("users")).isEqualTo(1)
        assertThat(socials.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "rejoin")!!.deletedAt).isNotNull()
        complete(pending["consentToken"].asText()).expectStatus().isOk
        val fresh = users.findAll().single { it.id != old.id }
        assertThat(fresh.status).isEqualTo(UserStatus.SUSPENDED)
        assertThat(fresh.rewardIdentityUserId).isEqualTo(old.id)
        assertThat(fresh.rejoinedAt).isNotNull()
        assertThat(count("credit_transactions")).isEqualTo(1)
        assertThat(count("user_consents")).isEqualTo(3)
    }

    @Test fun `게이트는 선택적 인증도 차단하고 허용 목록과 DELETED 401을 보존한다`() {
        val access = token("gated")["accessToken"].asText()
        client.get().uri("/api/v1/stories").header("Authorization", "Bearer $access").exchange()
            .expectStatus().isForbidden.expectBody().jsonPath("$.code").isEqualTo("CONSENT_REQUIRED")
        for (path in listOf("/auth/me", "/users/me/consents", "/credits/policies", "/credits/products", "/profile-presets", "/stories/simple/tags")) {
            client.get().uri("/api/v1$path").header("Authorization", "Bearer $access").exchange().expectStatus().isOk
        }
        client.get().uri("/api/v1/stories").exchange().expectStatus().isOk
        val user = users.findAll().single().apply { status = UserStatus.DELETED }
        users.saveAndFlush(user)
        for (path in listOf("/stories", "/auth/me", "/credits/policies")) {
            client.get().uri("/api/v1$path").header("Authorization", "Bearer $access").exchange().expectStatus().isUnauthorized
        }
    }

    @Test fun `카카오도 동일 계약이며 유효하지 않은 ID 토큰은 계정을 만들지 않는다`() {
        client.post().uri("/api/v1/auth/social/google").contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"invalid"}""")
            .exchange().expectStatus().isUnauthorized
        complete(start("kakao-sub", "kakao")["consentToken"].asText()).expectStatus().isOk
        assertThat(socials.findAll().single().provider).isEqualTo(SocialProvider.KAKAO)
    }

    @Test fun `완료의 사용자 행 잠금 이후 DELETED 상태면 동의를 기록하지 않는다`() {
        token("deleted-before-record")
        val user = users.findAll().single().apply { status = UserStatus.DELETED }
        users.saveAndFlush(user)
        val error = org.assertj.core.api.Assertions.catchThrowable {
            consentService.recordForAuthentication(user.id, UserConsentRequest("v1.4", "v1.7", "1"))
        }
        assertThat(error).isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat((error as org.springframework.web.server.ResponseStatusException).statusCode.value()).isEqualTo(401)
        assertThat(count("user_consents")).isZero()
    }

    @Test fun `동의 저장 실패는 신규 계정과 소셜 연동을 함께 롤백한다`() {
        val code = start("rollback-sub")["consentToken"].asText()
        jdbc.execute("ALTER TABLE user_consents ADD CONSTRAINT consent_test_failure CHECK (doc_type <> 'PRIVACY')")
        try {
            complete(code).expectStatus().is5xxServerError.expectBody().jsonPath("$.message").isEqualTo("서버 오류가 발생했습니다.")
            assertThat(count("users")).isZero()
            assertThat(count("social_accounts")).isZero()
            assertThat(count("user_consents")).isZero()
            assertThat(codes.find(code)).isNotNull()
        } finally { jdbc.execute("ALTER TABLE user_consents DROP CONSTRAINT consent_test_failure") }
        complete(code).expectStatus().isOk
    }

    private fun suspendedBundle(): Long {
        val access = token("bundle-google")["accessToken"].asText()
        val old = users.findAll().single()
        val inviter = users.saveAndFlush(com.knk.manyak.auth.entity.User(nickname = "초대자"))
        old.status = UserStatus.SUSPENDED
        old.inviterUserId = inviter.id
        old.migrationAttempts = 4
        old.rewardIdentityUserId = old.id
        users.saveAndFlush(old)
        socials.saveAndFlush(com.knk.manyak.auth.entity.SocialAccount(
            userId = old.id, provider = SocialProvider.KAKAO, providerUserId = "bundle-kakao",
        ))
        client.delete().uri("/api/v1/users/me").header("Authorization", "Bearer $access")
            .exchange().expectStatus().isNoContent
        val withdrawn = users.findById(old.id).orElseThrow()
        assertThat(withdrawn.status).isEqualTo(UserStatus.DELETED)
        assertThat(withdrawn.withdrawnFromStatus).isEqualTo(UserStatus.SUSPENDED)
        assertThat(socials.findByUserId(old.id)).hasSize(2).allSatisfy { assertThat(it.deletedAt).isNotNull() }
        return old.id
    }

    private fun assertRejoinedBundle(oldId: Long) {
        val old = users.findById(oldId).orElseThrow()
        val bundle = socials.findAll()
        assertThat(bundle).hasSize(2)
        assertThat(bundle.map { it.provider }).containsExactlyInAnyOrder(SocialProvider.GOOGLE, SocialProvider.KAKAO)
        assertThat(bundle.map { it.userId }.distinct()).hasSize(1)
        assertThat(bundle).allSatisfy { assertThat(it.deletedAt).isNull() }
        val fresh = users.findById(bundle.first().userId).orElseThrow()
        assertThat(fresh.id).isNotEqualTo(oldId)
        assertThat(fresh.status).isEqualTo(UserStatus.SUSPENDED)
        assertThat(fresh.inviterUserId).isEqualTo(old.inviterUserId).isNotNull()
        assertThat(fresh.migrationAttempts).isEqualTo(4)
        assertThat(fresh.rewardIdentityUserId).isEqualTo(old.rewardIdentityUserId)
        assertThat(fresh.rejoinedAt).isNotNull()
        assertThat(count("users")).isEqualTo(3) // 이전 회원, 초대자, 재가입 회원
        assertThat(count("user_consents")).isEqualTo(3)
        assertThat(count("credit_transactions")).isEqualTo(1)
    }

    @Test fun `새 완료는 같은 이전 회원의 Google Kakao 묶음과 정지 및 자격 표식을 승계한다`() {
        val oldId = suspendedBundle()
        val google = start("bundle-google")["consentToken"].asText()
        val kakao = start("bundle-kakao", "kakao")["consentToken"].asText()
        complete(google).expectStatus().isOk.expectBody().jsonPath("$.isNewUser").isEqualTo(true)
        assertRejoinedBundle(oldId)
        val agreed = jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")
        complete(kakao).expectStatus().isOk.expectBody().jsonPath("$.isNewUser").isEqualTo(false)
        assertRejoinedBundle(oldId)
        assertThat(jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")).isEqualTo(agreed)
    }

    @Test fun `재가입 동의 실패는 소셜 묶음과 이전 소유자의 모든 승계 표식을 롤백한다`() {
        val oldId = suspendedBundle()
        val beforeUsers = jdbc.queryForList("SELECT * FROM users ORDER BY id")
        val beforeSocials = jdbc.queryForList("SELECT * FROM social_accounts ORDER BY id")
        val code = start("bundle-kakao", "kakao")["consentToken"].asText()
        jdbc.execute("ALTER TABLE user_consents ADD CONSTRAINT consent_test_failure CHECK (doc_type <> 'PRIVACY')")
        try {
            complete(code).expectStatus().is5xxServerError
            // 전체 행 비교로 owner, deleted_at, 정지, inviter, migrationAttempts, rewardIdentity를 함께 고정한다.
            assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(beforeUsers)
            assertThat(jdbc.queryForList("SELECT * FROM social_accounts ORDER BY id")).isEqualTo(beforeSocials)
            assertThat(count("user_consents")).isZero()
            assertThat(count("credit_transactions")).isEqualTo(1)
            assertThat(codes.find(code)).isNotNull()
        } finally { jdbc.execute("ALTER TABLE user_consents DROP CONSTRAINT consent_test_failure") }
        complete(code).expectStatus().isOk
        assertRejoinedBundle(oldId)
    }

    @Test fun `인증 JSON 파싱 실패 DEBUG 로그는 원문 없이 경로 종류 요청 ID만 남기고 일반 API는 보존한다`() {
        val access = token("log-control")["accessToken"].asText()
        val logger = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.error.GlobalExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val original = logger.level
        val appender = object : ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(event: ch.qos.logback.classic.spi.ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        logger.level = ch.qos.logback.classic.Level.DEBUG
        logger.addAppender(appender)
        try {
            val secret = "ReviewSyntheticSecretToken"
            for (path in listOf("social/google", "social/kakao", "social/complete", "login/google", "login/kakao")) {
                appender.list.clear()
                val uri = "/api/v1/auth/$path"
                client.post().uri(uri).header("X-Manyak-Request-Id", "req-parse-test")
                    .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":$secret}""")
                    .exchange().expectStatus().isBadRequest
                val event = appender.list.single()
                assertThat(event.level).isEqualTo(ch.qos.logback.classic.Level.DEBUG)
                assertThat(event.formattedMessage).isEqualTo(
                    "Malformed request body: path=$uri, error_type=HttpMessageNotReadableException, request_id=req-parse-test",
                )
                assertThat(event.formattedMessage).doesNotContain(secret)
                assertThat(event.argumentArray.map { it.toString() }.joinToString()).doesNotContain(secret)
                assertThat(event.throwableProxy).isNull()
            }
            appender.list.clear()
            client.post().uri("/api/v1/users/me/consents").header("Authorization", "Bearer $access")
                .contentType(MediaType.APPLICATION_JSON).body("""{"terms":$secret}""")
                .exchange().expectStatus().isBadRequest
            assertThat(appender.list.single().formattedMessage).contains("message=", secret)
        } finally {
            logger.detachAppender(appender)
            logger.level = original
            appender.stop()
        }
    }

    @Test fun `보상 저장 실패 후 완료 재시도는 동의 시각과 계정을 보존하고 보상을 한번 지급한다`() {
        val code = start("retry")["consentToken"].asText()
        jdbc.execute("ALTER TABLE credit_transactions ADD CONSTRAINT reward_test_failure CHECK (reason <> 'SIGNUP_REWARD')")
        try {
            complete(code).expectStatus().is5xxServerError
            assertThat(count("users")).isEqualTo(1)
            assertThat(count("user_consents")).isEqualTo(3)
            assertThat(count("credit_transactions")).isZero()
            assertThat(codes.find(code)).isNotNull()
        } finally { jdbc.execute("ALTER TABLE credit_transactions DROP CONSTRAINT reward_test_failure") }
        val before = jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")
        complete(code).expectStatus().isOk.expectBody().jsonPath("$.isNewUser").isEqualTo(false)
        assertThat(jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")).isEqualTo(before)
        assertThat(count("users")).isEqualTo(1)
        assertThat(count("credit_transactions")).isEqualTo(1)
    }

    @Test fun `코드 소비 실패는 성공 이벤트를 보내지 않고 재시도에서 한 번 보낸다`() {
        val code = start("consume-retry")["consentToken"].asText()
        org.mockito.Mockito.doThrow(org.springframework.data.redis.RedisConnectionFailureException("redis unavailable"))
            .`when`(codes).consume(code)
        complete(code).expectStatus().is5xxServerError
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations.count { it.method.name == "socialLoginSucceeded" }).isZero()
        val before = jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")
        doCallRealMethod().`when`(codes).consume(code)
        complete(code).expectStatus().isOk
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations.count { it.method.name == "socialLoginSucceeded" }).isEqualTo(1)
        assertThat(jdbc.queryForList("SELECT * FROM user_consents ORDER BY doc_type")).isEqualTo(before)
        assertThat(count("credit_transactions")).isEqualTo(1)
    }

    @Test fun `동시 완료는 코드를 함께 읽어도 계정 동의 보상을 중복 생성하지 않는다`() {
        val code = start("concurrent")["consentToken"].asText()
        val barrier = CyclicBarrier(2)
        doAnswer { invocation ->
            val pending = invocation.callRealMethod()
            barrier.await(10, TimeUnit.SECONDS)
            pending
        }.`when`(codes).find(code)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<com.knk.manyak.auth.dto.TokenResponse> {
                service.complete(code, UserConsentRequest("v1.4", "v1.7", "1"), null)
            } }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertThat(results.count { it.isNewUser }).isEqualTo(1)
            assertThat(results.map { it.accessToken }).allSatisfy { assertThat(it).isNotBlank() }
        } finally {
            pool.shutdownNow()
            doCallRealMethod().`when`(codes).find(code)
        }
        assertThat(count("users")).isEqualTo(1)
        assertThat(count("social_accounts")).isEqualTo(1)
        assertThat(count("user_consents")).isEqualTo(3)
        assertThat(count("credit_transactions")).isEqualTo(1)
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations.count { it.method.name == "socialLoginSucceeded" }).isEqualTo(2)
        complete(code).expectStatus().isUnauthorized
    }

    @Test fun `핸드오프 원본 디바이스는 대기 중 만료해도 새 헤더보다 우선한다`() {
        trials.reserve("original-device", GuestTrialLimitService.Counter.CHAT_TURN)
        val handoff = handoffs.create(LoginHandoffCreateRequest(callbackPath = "/", sourceApp = "kakaotalk"), "original-device")
        val pending = json(client.post().uri("/api/v1/auth/social/google").header("X-Manyak-Device-Id", "external-device")
            .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"handoff-user","handoffCode":"${handoff.handoffCode}"}""")
            .exchange().expectStatus().isOk)
        val code = pending["consentToken"].asText()
        assertThat(codes.find(code)!!.deviceId).isEqualTo("original-device")
        assertThat(handoffs.find(handoff.handoffCode)!!.deviceId).isEqualTo("original-device")
        redis.keys("login_handoff:*").forEach { redis.expire(it, java.time.Duration.ZERO) }
        client.post().uri("/api/v1/auth/social/complete").header("X-Manyak-Consent-Token", code)
            .header("X-Manyak-Device-Id", "completion-device").contentType(MediaType.APPLICATION_JSON).body(ALL)
            .exchange().expectStatus().isOk
        assertThat(trials.usage(users.findAll().single().id, null, GuestTrialLimitService.Counter.CHAT_TURN).used).isEqualTo(1)
    }

    @Test fun `Redis 대기 코드는 해시 키와 TTL만 남기고 ID 토큰 원문은 저장하지 않는다`() {
        val result = start("stale:identity")
        val code = result["consentToken"].asText()
        val key = redis.keys("social_consent:*").single()
        assertThat(key).doesNotContain(code)
        assertThat(redis.getExpire(key)).isBetween(1L, 600L)
        assertThat(redis.opsForValue().get(key)).doesNotContain("stale:identity", "idToken")
    }

    @Test fun `Swagger는 두 상태 응답과 동의 헤더 및 요청 예시를 노출한다`() {
        val api = json(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk)
        val start = api["paths"]["/api/v1/auth/social/{provider}"]["post"]
        assertThat(start["responses"]["200"]["content"]["application/json"]["examples"].size()).isEqualTo(2)
        assertThat(api["components"]["schemas"]["SocialAuthResponse"]["properties"].has("isNewUser")).isTrue()
        val complete = api["paths"]["/api/v1/auth/social/complete"]["post"]
        assertThat(complete["parameters"].toString()).contains("X-Manyak-Consent-Token")
        assertThat(complete["requestBody"].toString()).contains("v1.4", "age14")
    }

    companion object { private const val ALL = """{"terms":"v1.4","privacy":"v1.7","age14":"1"}""" }
}
