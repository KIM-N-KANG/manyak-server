package com.knk.manyak.push.config

import com.google.firebase.FirebaseApp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * FCM 빈 생성 경로 고정(KNK-1130).
 * - 서비스 계정 JSON이 비어 있으면 null(→ NullBean, 발송 no-op).
 * - JSON이 있으면 네트워크 없이 FirebaseMessaging까지 만들어진다. firebase-admin에서 Firestore·Storage를 exclude했으므로,
 *   초기화가 그 클래스를 건드리면 여기서 NoClassDefFoundError로 드러난다.
 * 키는 테스트 안에서 즉석 생성한 일회용 RSA 키다. 비밀이 아니다.
 */
class FcmConfigTest {

    @AfterEach
    fun tearDown() {
        // FirebaseApp은 JVM 전역 싱글턴이라 다른 테스트에 남기지 않는다.
        FirebaseApp.getApps().toList().forEach { it.delete() }
    }

    @Test
    fun `서비스 계정 JSON이 비어 있으면 빈을 만들지 않는다`() {
        assertThat(FcmConfig().firebaseMessaging("   ")).isNull()
        assertThat(FirebaseApp.getApps()).isEmpty()
    }

    @Test
    fun `서비스 계정 JSON이 있으면 FirebaseMessaging을 만든다`() {
        val messaging = FcmConfig().firebaseMessaging(fakeServiceAccountJson())

        assertThat(messaging).isNotNull
        assertThat(FirebaseApp.getApps()).hasSize(1)
    }

    @Test
    fun `이미 초기화된 앱이 있으면 재사용한다`() {
        val json = fakeServiceAccountJson()
        FcmConfig().firebaseMessaging(json)

        assertThat(FcmConfig().firebaseMessaging(json)).isNotNull
        assertThat(FirebaseApp.getApps()).hasSize(1)
    }

    private fun fakeServiceAccountJson(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return """
            {
              "type": "service_account",
              "project_id": "manyak-test",
              "private_key_id": "test-key",
              "private_key": "${pem.replace("\n", "\\n")}",
              "client_email": "fcm-test@manyak-test.iam.gserviceaccount.com",
              "client_id": "1",
              "token_uri": "https://oauth2.googleapis.com/token"
            }
        """.trimIndent()
    }
}
