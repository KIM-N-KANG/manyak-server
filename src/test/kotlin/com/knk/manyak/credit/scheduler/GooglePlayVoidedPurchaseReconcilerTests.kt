package com.knk.manyak.credit.scheduler

import com.knk.manyak.credit.config.GooglePlayPaymentProperties
import com.knk.manyak.credit.google.GooglePlayPurchaseClient
import com.knk.manyak.credit.google.GoogleVoidedPurchase
import com.knk.manyak.credit.service.GooglePlayOrderTransactions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GooglePlayVoidedPurchaseReconcilerTests {
    private val google = mock(GooglePlayPurchaseClient::class.java)
    private val orders = mock(GooglePlayOrderTransactions::class.java)
    private val meters = SimpleMeterRegistry()
    private val scheduler = GooglePlayVoidedPurchaseReconciler(GooglePlayPaymentProperties("test-only", "app.test"), google, orders, meters)

    @Test fun `겹치는 실행은 건너뛰고 조회 실패 뒤 다음 실행은 재시도한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(google.listVoidedPurchases(anyString(), anyLong())).thenAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            throw IllegalStateException("test-only")
        }.thenReturn(emptyList())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit { scheduler.run() }
            check(entered.await(10, TimeUnit.SECONDS))
            scheduler.run()
            verify(google, times(1)).listVoidedPurchases(anyString(), anyLong())
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            scheduler.run()
            verify(google, times(2)).listVoidedPurchases(anyString(), anyLong())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `회수 결과 카운터를 기록하고 실패 건 다음 항목도 처리한다`() {
        `when`(google.listVoidedPurchases(anyString(), anyLong())).thenReturn(listOf(
            GoogleVoidedPurchase("first"), GoogleVoidedPurchase("second"), GoogleVoidedPurchase("third")))
        `when`(orders.reverse(anyString())).thenThrow(IllegalStateException()).thenReturn("reversed", "ignored")
        scheduler.run()
        assertThat(meters.counter("manyak.payment.google.voided", "result", "reversed").count()).isEqualTo(1.0)
        assertThat(meters.counter("manyak.payment.google.voided", "result", "ignored").count()).isEqualTo(1.0)
    }
}
