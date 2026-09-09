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
        // 빈 설정으로도 기동한다. 자격증명 파싱·토큰 갱신은 실제 호출 때만 하며 값을 로그에 남기지 않는다.
        val credentials by lazy {
            GoogleCredentials.fromStream(properties.serviceAccountJson.byteInputStream())
                .createScoped("https://www.googleapis.com/auth/androidpublisher")
        }
        val rest = RestClient.builder().baseUrl("https://androidpublisher.googleapis.com")
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(15))
            }).build()
        return RestGooglePlayPurchaseClient(rest) {
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        }
    }
}
