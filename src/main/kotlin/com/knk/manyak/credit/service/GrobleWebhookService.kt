package com.knk.manyak.credit.service

import com.knk.manyak.credit.config.GroblePaymentProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 본문을 역직렬화하기 전에 수신한 바이트 그대로 서명을 검증한다. */
@Service
class GrobleWebhookService(
    private val properties: GroblePaymentProperties,
    private val mapper: ObjectMapper,
    private val events: GroblePaymentEventService,
    private val meters: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun receive(body: ByteArray, timestamp: String?, signature: String?, previous: String?, deliveryKey: String?) {
        if (properties.webhookSecret.isBlank()) invalid(HttpStatus.SERVICE_UNAVAILABLE)
        val seconds = timestamp?.toLongOrNull() ?: invalid(HttpStatus.UNAUTHORIZED)
        val now = clock.instant().epochSecond
        if (seconds < now - 300 || seconds > now + 300) invalid(HttpStatus.UNAUTHORIZED)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.webhookSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$timestamp.".toByteArray(Charsets.UTF_8))
        val expected = mac.doFinal(body)
        if (!(matches(expected, signature) or matches(expected, previous))) invalid(HttpStatus.UNAUTHORIZED)
        val event = try {
            mapper.readTree(body)
        } catch (_: JacksonException) {
            invalid(HttpStatus.BAD_REQUEST)
        }
        if (event == null || !event.isObject) invalid(HttpStatus.BAD_REQUEST)
        // 별도 빈의 트랜잭션 커밋이 성공한 뒤 처리 결과를 센다.
        val result = events.process(event, deliveryKey)
        meters.counter(METRIC, "result", result).increment()
    }

    private fun matches(expected: ByteArray, signature: String?): Boolean {
        if (signature == null || signature.length != 64) return false
        val actual = try { HexFormat.of().parseHex(signature) } catch (_: IllegalArgumentException) { return false }
        return MessageDigest.isEqual(expected, actual)
    }

    private fun invalid(status: HttpStatus): Nothing {
        meters.counter(METRIC, "result", "invalid").increment()
        throw ResponseStatusException(status, "그로블 웹훅 검증 실패")
    }

    private companion object {
        const val METRIC = "manyak.payment.groble.webhook"
    }
}
