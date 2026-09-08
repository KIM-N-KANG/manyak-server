package com.knk.manyak.credit.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class GroblePaymentPropertiesTests {
    private val values = listOf(2000, 5000, 10000, 30000, 50000).flatMapIndexed { i, value ->
        listOf(
            "manyak.payment.groble.products[$i].id=if_$value",
            "manyak.payment.groble.products[$i].base=$value",
            "manyak.payment.groble.products[$i].bonus=0",
            "manyak.payment.groble.products[$i].web-price-krw=$value",
            "manyak.payment.groble.products[$i].app-price-krw=${value * 14 / 10}",
        )
    }
    private fun runner(properties: List<String> = values) = ApplicationContextRunner()
        .withUserConfiguration(GroblePaymentConfig::class.java)
        .withPropertyValues(*properties.toTypedArray())

    @Test
    fun `유효한 5종은 바인딩하고 미설정 링크와 시크릿으로도 기동한다`() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(GroblePaymentProperties::class.java)
            assertThat(properties.products).hasSize(5)
            assertThat(properties.products.first().id).isEqualTo("if_2000")
            assertThat(properties.products.first().paymentUrl).isEmpty()
            assertThat(properties.webhookSecret).isEmpty()
        }
    }

    @Test
    fun `상품이 5종이 아니면 바인딩 중 기동에 실패한다`() {
        runner(values.filterNot { "products[4]" in it }).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("이프 충전 상품은 5종이어야 합니다.")
        }
    }

    @Test
    fun `중복 상품 ID는 바인딩 중 기동에 실패한다`() {
        runner().withPropertyValues("manyak.payment.groble.products[1].id=if_2000").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("이프 상품 ID는 중복될 수 없습니다.")
        }
    }

    @Test
    fun `기본량과 가격은 양수이고 보너스는 음수가 아니어야 한다`() {
        for ((field, value) in listOf("base" to "0", "web-price-krw" to "0", "app-price-krw" to "-1", "bonus" to "-1")) {
            runner().withPropertyValues("manyak.payment.groble.products[0].$field=$value").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("기본 이프·가격은 양수, 보너스는 0 이상이어야 합니다.")
            }
        }
    }
}
