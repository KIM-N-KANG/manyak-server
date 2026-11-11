package com.knk.manyak.global.observability.analytics

import com.knk.manyak.auth.dto.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.MdcKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.MDC

/**
 * 로그인 분석 이벤트의 와이어 계약(이벤트명, 식별자, 프로퍼티 전체)을 제공자별로 고정한다(KNK-2068).
 * 제공자 인자 타입을 바꾸는 동안 발행 결과가 그대로인지 확인하는 안전망이다. GOOGLE, KAKAO만 발행하고 나머지는 생략한다.
 */
class ServerAnalyticsLoginContractTest {

    private class CapturingPublisher : AnalyticsEventPublisher {
        val events = mutableListOf<AnalyticsEvent>()
        override fun publish(event: AnalyticsEvent) {
            events.add(event)
        }
    }

    private val publisher = CapturingPublisher()
    private val analytics = ServerAnalytics(publisher, mock(UserRepository::class.java))

    @AfterEach fun clearMdc() = MDC.clear()

    private fun succeeded(provider: SocialProvider) = analytics.socialLoginSucceeded(provider, MEMBER, isNewUser = true, isRejoined = false)

    private fun failed(provider: SocialProvider) = analytics.socialLoginFailed(provider, AnalyticsErrorType.VALIDATION)

    @Test
    fun `제공자별 로그인 성공과 실패 이벤트의 이름과 프로퍼티 전체가 고정돼 있다`() {
        val expected = mapOf(
            SocialProvider.GOOGLE to ("server_login_googleLogin_processed_succeeded" to "server_login_googleLogin_processed_failed"),
            SocialProvider.KAKAO to ("server_login_kakaoLogin_processed_succeeded" to "server_login_kakaoLogin_processed_failed"),
        )
        for (provider in SocialProvider.entries) {
            publisher.events.clear()
            MDC.put(MdcKeys.REQUEST_ID, "req-1")
            MDC.put(MdcKeys.DEVICE_ID_HASH, "hash-1")
            succeeded(provider)
            failed(provider)

            val names = expected[provider]
            if (names == null) {
                assertThat(publisher.events).describedAs("$provider").isEmpty()
                continue
            }
            val (ok, ng) = publisher.events
            assertThat(ok.eventType).isEqualTo(names.first)
            assertThat(ok.userId).isEqualTo(MEMBER)
            assertThat(ok.deviceId).isNull()
            assertThat(ok.insertId).isEqualTo("req-1:${names.first}")
            assertThat(ok.eventProperties).containsExactlyInAnyOrderEntriesOf(
                mapOf("is_logged_in" to true, "request_id" to "req-1", "device_id_hash" to "hash-1", "is_new_user" to true, "is_rejoined" to false),
            )
            assertThat(ng.eventType).isEqualTo(names.second)
            assertThat(ng.userId).isNull()
            assertThat(ng.deviceId).isEqualTo("unknown")
            assertThat(ng.insertId).isEqualTo("req-1:${names.second}")
            assertThat(ng.eventProperties).containsExactlyInAnyOrderEntriesOf(
                mapOf("is_logged_in" to false, "request_id" to "req-1", "device_id_hash" to "hash-1", "error_type" to "validation"),
            )
            assertThat(publisher.events).hasSize(2)
        }
    }

    private companion object {
        const val MEMBER = "11111111-2222-3333-4444-555555555555"
    }
}
