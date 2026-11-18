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
    fun `릴레이가 정한 추적 헤더를 Kafka 레코드 헤더에 담는다`() {
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val mapper = ObjectMapper()
        val message = PushMessage("id", "recipient", data = emptyMap(), requestId = "unknown", sessionId = "unknown")
        `when`(kafka.send(any<ProducerRecord<String, String>>())).thenAnswer {
            val record = it.getArgument<ProducerRecord<String, String>>(0)
            assertThat(record.topic()).isEqualTo("push.requested")
            assertThat(record.key()).isEqualTo(message.recipientId)
            assertThat(record.value()).isEqualTo(mapper.writeValueAsString(message))
            assertThat(String(record.headers().lastHeader("traceparent").value(), Charsets.UTF_8)).isEqualTo("00-trace-span-01")
            assertThat(String(record.headers().lastHeader("tracestate").value(), Charsets.UTF_8)).isEqualTo("vendor=value")
            CompletableFuture.completedFuture<SendResult<String, String>>(null)
        }
        KafkaPushPublisher(kafka, mapper).use { publisher ->
            publisher.publish(message, mapOf("traceparent" to "00-trace-span-01", "tracestate" to "vendor=value")).join()
        }
        verify(kafka).send(any<ProducerRecord<String, String>>())
    }

    @Test
    fun `추적 헤더가 없으면 레코드 헤더를 붙이지 않는다`() {
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val message = PushMessage("id", "recipient", data = emptyMap(), requestId = "unknown", sessionId = "unknown")
        `when`(kafka.send(any<ProducerRecord<String, String>>())).thenAnswer {
            assertThat(it.getArgument<ProducerRecord<String, String>>(0).headers().toArray()).isEmpty()
            CompletableFuture.completedFuture<SendResult<String, String>>(null)
        }
        KafkaPushPublisher(kafka, ObjectMapper()).use { it.publish(message, emptyMap()).join() }
        verify(kafka).send(any<ProducerRecord<String, String>>())
    }
}
