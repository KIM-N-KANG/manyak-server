package com.knk.manyak.auth.handoff

import com.knk.manyak.auth.social.GoogleIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.DeviceIdHasher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 핸드오프 통합 테스트가 **공유하는** 테스트 설정.
 *
 * 중첩 `@TestConfiguration`을 클래스마다 두면 설정 키가 달라져 테스트 클래스 수만큼 Spring 컨텍스트가 뜬다.
 * 컨텍스트 하나가 앱·Tomcat·H2·커넥션풀을 통째로 들고 있어, CI 러너에서는 그 압박이 다른 테스트의 HTTP
 * 호출 타임아웃으로 번진다(실제로 이 PR에서 관측). 최상위 설정 하나를 `@Import`로 공유해 컨텍스트를 하나로 묶는다.
 */
@TestConfiguration
class LoginHandoffTestConfig {

    /** "invalid"만 401, 그 외에는 토큰 문자열을 provider 식별자로 쓰는 가짜 검증기(외부 IO 없음). */
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

    /**
     * 시드 실패(Redis 장애)를 테스트에서 켜고 끌 수 있는 체험 한도 서비스.
     * 한도값은 실제 설정 해석 경로를 그대로 써 프로덕션 기본값과 어긋나지 않게 한다.
     */
    @Bean
    @Primary
    fun toggleableGuestTrialLimitService(
        redisTemplate: StringRedisTemplate,
        deviceIdHasher: DeviceIdHasher,
        @Value("\${manyak.guest-trial.storyline-generation-limit:5}") storylineGenerationLimit: Long,
        @Value("\${manyak.guest-trial.story-creation-limit:1}") storyCreationLimit: Long,
        @Value("\${manyak.guest-trial.chat-turn-limit:5}") chatTurnLimit: Long,
    ): ToggleableGuestTrialLimitService = ToggleableGuestTrialLimitService(
        redisTemplate,
        deviceIdHasher,
        storylineGenerationLimit,
        storyCreationLimit,
        chatTurnLimit,
    )
}

/**
 * 가입 시드만 실패로 뒤집을 수 있는 [GuestTrialLimitService]. 그 외 동작은 실제 구현을 그대로 쓴다
 * (디바이스 헤더 검증·게스트/회원 카운터가 실제 Redis 경로를 타야 검증이 성립한다).
 */
class ToggleableGuestTrialLimitService(
    redisTemplate: StringRedisTemplate,
    deviceIdHasher: DeviceIdHasher,
    storylineGenerationLimit: Long,
    storyCreationLimit: Long,
    chatTurnLimit: Long,
) : GuestTrialLimitService(
    redisTemplate,
    deviceIdHasher,
    storylineGenerationLimit,
    storyCreationLimit,
    chatTurnLimit,
) {

    /** true면 시드가 실패(false)를 돌려준다 — Redis 장애로 미시드로 남는 경로를 흉내 낸다. */
    @Volatile
    var failSnapshot: Boolean = false

    override fun snapshotTrialAtSignup(userId: Long, deviceId: String?): Boolean =
        if (failSnapshot) false else super.snapshotTrialAtSignup(userId, deviceId)
}
