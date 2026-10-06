package com.knk.manyak.push.outbox

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.CompletableFuture

class SqsPushPublisher(
    private val sqs: SqsAsyncClient,
    private val mapper: ObjectMapper,
    private val queueUrl: String,
    private val traceHeaders: TraceHeaders = TraceHeaders.NONE,
) : PushPublisher {
    override fun publish(message: PushMessage): CompletableFuture<Unit> =
        try {
            // traceparent 등 추적 헤더는 본문이 아니라 메시지 속성으로 싣는다. 본문 계약(schemaVersion)을 건드리지 않는다.
            val attributes = LinkedHashMap<String, MessageAttributeValue>()
            traceHeaders.inject { name, value ->
                attributes[name] = MessageAttributeValue.builder().dataType("String").stringValue(value).build()
            }
            val request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(mapper.writeValueAsString(message))
                .apply { if (attributes.isNotEmpty()) messageAttributes(attributes) }
                .build()
            sqs.sendMessage(request).thenApply { Unit }
        } catch (ex: Exception) {
            CompletableFuture.failedFuture(ex)
        }
}

@Configuration(proxyBeanMethods = false)
@Profile("dev | prod")
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class SqsPushConfig {
    @Bean
    fun pushSqsClient(
        @Value("\${manyak.push.queue-url:}") queueUrl: String,
        @Value("\${manyak.push.region:}") region: String,
        settings: PushOutboxProperties,
    ): SqsAsyncClient {
        require(queueUrl.isNotBlank()) { "manyak.push.queue-url must not be blank in remote mode" }
        require(settings.sendTimeout > API_CALL_TIMEOUT) {
            "outbox send-timeout must exceed SQS apiCallTimeout (10s)"
        }
        return SqsAsyncClient.builder()
            .apply { if (region.isNotBlank()) region(Region.of(region)) }
            // S3와 동일하게 기본 자격증명 체인을 사용한다(ECS 태스크 역할).
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .apiCallTimeout(API_CALL_TIMEOUT)
                    .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                    .build(),
            )
            .build()
    }

    @Bean
    fun pushPublisher(
        sqs: SqsAsyncClient,
        mapper: ObjectMapper,
        @Value("\${manyak.push.queue-url:}") queueUrl: String,
        traceHeaders: ObjectProvider<TraceHeaders>,
    ) = SqsPushPublisher(sqs, mapper, queueUrl, traceHeaders.getIfAvailable { TraceHeaders.NONE })

    companion object {
        // 재시도를 포함한 SDK 호출 < 배치 전송 제한 < 임대.
        private val API_CALL_TIMEOUT = Duration.ofSeconds(10)
        private val API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5)
    }
}
