package com.knk.manyak.credit.scheduler

import com.knk.manyak.credit.config.GooglePlayPaymentProperties
import com.knk.manyak.credit.google.GooglePlayPurchaseClient
import com.knk.manyak.credit.service.GooglePlayOrderTransactions
import com.knk.manyak.credit.service.purchaseTokenHash
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

@Component
@ConditionalOnProperty(name = ["manyak.payment.google-play.voided-reconcile.enabled"], havingValue = "true", matchIfMissing = true)
class GooglePlayVoidedPurchaseReconciler(
    private val properties: GooglePlayPaymentProperties,
    private val google: GooglePlayPurchaseClient,
    private val orders: GooglePlayOrderTransactions,
    private val meters: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val running = AtomicBoolean(false)

    // 기존 대사와 같이 fixedDelay + 주문 행 락으로 다중 인스턴스 중복 회수를 막는다.
    // 수동 호출과 스케줄 호출의 단일 인스턴스 겹침도 막는다. ShedLock 의존성은 추가하지 않는다.
    @Scheduled(fixedDelayString = "\${manyak.payment.google-play.voided-reconcile.fixed-delay:1h}",
        initialDelayString = "\${manyak.payment.google-play.voided-reconcile.fixed-delay:1h}")
    fun run() {
        if (!properties.configured || !properties.voidedReconcile.enabled || !running.compareAndSet(false, true)) return
        try {
            val purchases = google.listVoidedPurchases(properties.packageName, clock.instant().minus(properties.voidedReconcile.lookback).toEpochMilli())
            for (purchase in purchases) {
                try {
                    val result = orders.reverse(purchaseTokenHash(purchase.purchaseToken))
                    meters.counter("manyak.payment.google.voided", "result", result).increment()
                } catch (_: Exception) {
                    // 건별 트랜잭션 실패를 격리한다. 다음 lookback 조회에서 다시 시도한다.
                    logger.warn("Google Play 환불 개별 회수 실패")
                }
            }
        } catch (_: Exception) {
            logger.warn("Google Play 환불 대사 조회 실패")
        } finally {
            running.set(false)
        }
    }

    private companion object { val logger = LoggerFactory.getLogger(GooglePlayVoidedPurchaseReconciler::class.java) }
}
