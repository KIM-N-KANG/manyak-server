package com.knk.manyak.credit.config

import com.google.auth.oauth2.GoogleCredentials
import com.knk.manyak.credit.google.GooglePlayPurchaseClient
import com.knk.manyak.credit.google.RestGooglePlayPurchaseClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@ConfigurationProperties("manyak.payment.google-play")
class GooglePlayPaymentProperties(
    val serviceAccountJson: String = "",
    val packageName: String = "",
    val allowTestPurchases: Boolean = false,
    val voidedReconcile: VoidedReconcile = VoidedReconcile(),
) {
    val configured: Boolean get() = serviceAccountJson.isNotBlank() && packageName.isNotBlank()

    class VoidedReconcile(
        val enabled: Boolean = true,
        val fixedDelay: Duration = Duration.ofHours(1),
        val lookback: Duration = Duration.ofHours(48),
    ) {
        init {
            require(!fixedDelay.isZero && !fixedDelay.isNegative) { "대사 주기는 양수여야 합니다." }
            require(!lookback.isZero && !lookback.isNegative && lookback <= Duration.ofDays(30)) {
                "Google Play 대사 조회 범위는 0일 초과 30일 이하여야 합니다."
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GooglePlayPaymentProperties::class)
class GooglePlayPaymentConfig {
    @Bean
    fun googlePlayPurchaseClient(properties: GooglePlayPaymentProperties): GooglePlayPurchaseClient {
        // 빈 값만 미설정으로 허용한다. 비공백 JSON은 기동 시 검증하고 토큰 발급은 실제 호출 때 한다.
        val credentials = if (properties.serviceAccountJson.isBlank()) null else try {
            GoogleCredentials.fromStream(properties.serviceAccountJson.byteInputStream())
                .createScoped("https://www.googleapis.com/auth/androidpublisher")
        } catch (_: Exception) {
            // JSON·개인 키가 파서 예외에 섞일 수 있어 원인 객체를 기동 로그에 노출하지 않는다.
            throw IllegalArgumentException("Google Play 서비스 계정 JSON이 올바르지 않습니다.")
        }
        val rest = RestClient.builder().baseUrl("https://androidpublisher.googleapis.com")
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(15))
            }).build()
        return RestGooglePlayPurchaseClient(rest) {
            val configuredCredentials = credentials ?: throw com.knk.manyak.credit.google.GooglePlayUnavailableException()
            configuredCredentials.refreshIfExpired()
            configuredCredentials.accessToken.tokenValue
        }
    }
}
