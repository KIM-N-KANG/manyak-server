package com.knk.manyak.credit.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

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

    @Test fun `설정과 기간을 바인딩하며 기동 중 Google 인증은 하지 않는다`() {
        runner().withPropertyValues("manyak.payment.google-play.service-account-json=test-only",
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
}
