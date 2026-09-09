package com.knk.manyak.credit.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.security.KeyPairGenerator
import java.util.Base64
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.ObjectMapper

class GooglePlayPaymentPropertiesTests {
    private fun runner() = ApplicationContextRunner().withUserConfiguration(GooglePlayPaymentConfig::class.java)

    @Test fun `빈 설정은 기동하고 안전한 기본값을 바인딩한다`() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            val p = context.getBean(GooglePlayPaymentProperties::class.java)
            assertThat(p.configured).isFalse()
            assertThat(p.allowTestPurchases).isFalse()
            assertThat(p.voidedReconcile.enabled).isTrue()
            assertThat(p.voidedReconcile.fixedDelay).isEqualTo(Duration.ofHours(1))
            assertThat(p.voidedReconcile.lookback).isEqualTo(Duration.ofHours(48))
        }
    }

    @Test fun `유효한 JSON은 기동 때 파싱하고 토큰 발급 없이 기간 설정을 바인딩한다`() {
        runner().withPropertyValues("manyak.payment.google-play.service-account-json=${validServiceAccountJson()}",
            "manyak.payment.google-play.package-name=app.manyak.test",
            "manyak.payment.google-play.allow-test-purchases=true",
            "manyak.payment.google-play.voided-reconcile.fixed-delay=2h",
            "manyak.payment.google-play.voided-reconcile.lookback=72h").run { context ->
            assertThat(context).hasNotFailed()
            val p = context.getBean(GooglePlayPaymentProperties::class.java)
            assertThat(p.configured).isTrue()
            assertThat(p.allowTestPurchases).isTrue()
            assertThat(p.voidedReconcile.fixedDelay).isEqualTo(Duration.ofHours(2))
            assertThat(p.voidedReconcile.lookback).isEqualTo(Duration.ofHours(72))
        }
    }

    @Test fun `Google 조회 한도 30일을 넘는 대사 설정은 거부한다`() {
        runner().withPropertyValues("manyak.payment.google-play.voided-reconcile.lookback=31d")
            .run { assertThat(it).hasFailed() }
    }
    @Test fun `비공백 malformed JSON은 기동에 실패한다`() {
        for (json in listOf("{malformed-test-only", "{}")) {
            runner().withPropertyValues("manyak.payment.google-play.service-account-json=$json")
                .run { assertThat(it).hasFailed() }
        }
    }

    @Test fun `공백 JSON은 미설정으로 기동한다`() {
        runner().withPropertyValues("manyak.payment.google-play.service-account-json=   ",
            "manyak.payment.google-play.package-name=app.manyak.test").run {
            assertThat(it).hasNotFailed()
            assertThat(it.getBean(GooglePlayPaymentProperties::class.java).configured).isFalse()
        }
    }

    @Test fun `기본과 dev 프로파일은 테스트 허용을 false true 리터럴로 바인딩한다`() {
        for ((file, expected) in listOf("application.yml" to false, "application-dev.yml" to true)) {
            val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource(file)) }.getObject()!!
            val value = yaml.getProperty("manyak.payment.google-play.allow-test-purchases")
            assertThat(value).isEqualTo(expected.toString())
            runner().withPropertyValues("manyak.payment.google-play.allow-test-purchases=$value").run {
                assertThat(it).hasNotFailed()
                assertThat(it.getBean(GooglePlayPaymentProperties::class.java).allowTestPurchases).isEqualTo(expected)
            }
        }
    }

    @Test fun `대사가 비활성화되면 스케줄러 빈을 만들지 않는다`() {
        runner().withUserConfiguration(com.knk.manyak.credit.scheduler.GooglePlayVoidedPurchaseReconciler::class.java)
            .withPropertyValues("manyak.payment.google-play.voided-reconcile.enabled=false").run {
                assertThat(it).hasNotFailed()
                assertThat(it).doesNotHaveBean(com.knk.manyak.credit.scheduler.GooglePlayVoidedPurchaseReconciler::class.java)
            }
    }

    private fun validServiceAccountJson(): String {
        // 외부 권한이 없는 테스트 전용 키를 메모리에서 생성한다. 토큰 발급·네트워크 호출은 하지 않는다.
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(key.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        return ObjectMapper().writeValueAsString(mapOf("type" to "service_account", "project_id" to "test-only",
            "private_key_id" to "test-only", "private_key" to pem, "client_email" to "test@test-only.iam.gserviceaccount.com",
            "client_id" to "123", "token_uri" to "https://oauth2.googleapis.com/token"))
    }

}
