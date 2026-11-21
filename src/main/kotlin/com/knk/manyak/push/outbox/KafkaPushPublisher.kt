package com.knk.manyak.push.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** send()의 메타데이터 대기도 배치 제출을 직렬로 막지 않도록 별도 가상 스레드에서 실행한다. */
class KafkaPushPublisher(
    private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
) : PushPublisher, AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    override fun publish(message: PushMessage, traceHeaders: Map<String, String>): CompletableFuture<Unit> {
        // 헤더는 릴레이가 자기 스레드에서 정해 넘긴다. 가상 스레드에서는 현재 스팬을 읽지 않는다.
        val headers = RecordHeaders()
        traceHeaders.forEach { (name, value) -> headers.add(name, value.toByteArray(Charsets.UTF_8)) }
        return CompletableFuture.supplyAsync({
            kafka.send(ProducerRecord("push.requested", null, null, message.recipientId, mapper.writeValueAsString(message), headers))
        }, executor).thenCompose { it }.thenApply { Unit }
    }
    override fun close() { executor.close() }
}

// 브로커는 프로필이 아니라 manyak.push.broker로 고른다(KNK-2125). 기본값은 local이 kafka, 그 밖은 sqs다(yml).
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
@ConditionalOnProperty(name = ["manyak.push.broker"], havingValue = "kafka")
class KafkaPushConfig {
    @Bean
    fun pushKafkaProducerFactory(
        @Value("\${spring.kafka.bootstrap-servers:localhost:9092}") servers: String,
        settings: PushOutboxProperties,
    ): DefaultKafkaProducerFactory<String, String> {
        // 메타데이터 획득(1초) + delivery(15초)가 배치 제한·임대보다 짧아야 한다.
        require(settings.sendTimeout.toMillis() > 16_000) { "outbox send-timeout must exceed Kafka max.block.ms + delivery.timeout.ms (16s)" }
        return DefaultKafkaProducerFactory(mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to servers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 15_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 10_000,
            ProducerConfig.LINGER_MS_CONFIG to 0,
            ProducerConfig.MAX_BLOCK_MS_CONFIG to 1_000,
        ))
    }
    @Bean
    fun pushKafkaTemplate(factory: DefaultKafkaProducerFactory<String, String>) = KafkaTemplate(factory)
    @Bean
    fun pushPublisher(kafka: KafkaTemplate<String, String>, mapper: ObjectMapper) = KafkaPushPublisher(kafka, mapper)
}
