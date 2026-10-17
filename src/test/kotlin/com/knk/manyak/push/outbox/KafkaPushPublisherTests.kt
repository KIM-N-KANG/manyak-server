package com.knk.manyak.push.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

class KafkaPushPublisherTests {
    @Test
    fun `비동기 제출 전에 호출 스레드의 추적 헤더를 Kafka 레코드에 담는다`() {
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val mapper = ObjectMapper()
        val message = PushMessage("id", "recipient", data = emptyMap(), requestId = "unknown", sessionId = "unknown")
        val caller = Thread.currentThread()
        var injected = false
        `when`(kafka.send(any<ProducerRecord<String, String>>())).thenAnswer {
            val record = it.getArgument<ProducerRecord<String, String>>(0)
            assertThat(record.topic()).isEqualTo("push.requested")
            assertThat(record.key()).isEqualTo(message.recipientId)
            assertThat(record.value()).isEqualTo(mapper.writeValueAsString(message))
            assertThat(String(record.headers().lastHeader("traceparent").value(), Charsets.UTF_8)).isEqualTo("00-trace-span-01")
            assertThat(String(record.headers().lastHeader("tracestate").value(), Charsets.UTF_8)).isEqualTo("vendor=value")
            CompletableFuture.completedFuture<SendResult<String, String>>(null)
        }
        // 기존 구현이 쓰는 오버로드도 완료시켜 헤더 주입 여부로 red를 확인한다.
        `when`(kafka.send("push.requested", message.recipientId, mapper.writeValueAsString(message)))
            .thenReturn(CompletableFuture.completedFuture(null))
        KafkaPushPublisher(kafka, mapper) { sink ->
            assertThat(Thread.currentThread()).isSameAs(caller)
            injected = true
            sink("traceparent", "00-trace-span-01")
            sink("tracestate", "vendor=value")
        }.use { publisher ->
            publisher.publish(message).join()
        }
        assertThat(injected).isTrue()
        verify(kafka).send(any<ProducerRecord<String, String>>())
    }
}
