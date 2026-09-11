package com.knk.manyak.push.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * FCM 클라이언트 빈(KNK-1130).
 *
 * 서비스 계정 JSON(`manyak.push.fcm.service-account-json`)이 비어 있으면 **빈을 만들지 않는다**(null 반환 → Spring이
 * NullBean으로 등록, 선택 주입은 null). 그러면 [com.knk.manyak.push.service.FcmPushSender]가 no-op으로 기동한다.
 * Slack 웹훅(미설정이면 건너뜀)과 같은 관례이며, 빈 값이 주입된 채 발송 시도가 전부 실패하는 OTLP류 사고(KNK-993)를
 * 기동 시점에 걸러 낸다. 로컬·테스트는 값이 없어 자연히 no-op이다.
 *
 * 서비스 계정은 앱의 google-services.json과 **같은 Firebase 프로젝트**에서 발급해야 한다. 프로젝트가 다르면
 * 앱이 등록한 토큰을 이 서버가 쓸 수 없다.
 */
@Configuration
class FcmConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firebaseMessaging(
        @Value("\${manyak.push.fcm.service-account-json:}") serviceAccountJson: String,
    ): FirebaseMessaging? {
        val json = serviceAccountJson.trim()
        if (json.isEmpty()) {
            log.info("FCM 서비스 계정이 설정되지 않아 푸시 발송을 비활성화합니다.")
            return null
        }
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(json.byteInputStream()))
            .build()
        // FirebaseApp은 JVM 전역 싱글턴이라 두 번 initializeApp 하면 IllegalStateException이다(컨텍스트 재기동 대비).
        val app = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(options)
        return FirebaseMessaging.getInstance(app)
    }
}
