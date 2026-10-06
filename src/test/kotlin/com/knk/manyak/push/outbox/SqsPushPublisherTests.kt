package com.knk.manyak.push.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

class SqsPushPublisherTests {
    private val client = mock(SqsAsyncClient::class.java)
    private val mapper = ObjectMapper()
    private val queueUrl = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/push-test"
    private val message = PushMessage(
        "story-completed:request", "73ddc496-cd60-4b04-905d-6b802af63b79",
        data = mapOf("type" to "STORY_COMPLETED", "title" to "완성된 스토리"),
        requestId = "request", sessionId = "session",
    )
    private val request = SendMessageRequest.builder()
        .queueUrl(queueUrl).messageBody(mapper.writeValueAsString(message)).build()
    private val publisher = SqsPushPublisher(client, mapper, queueUrl)

    @Test
    fun `큐 URL과 Kafka와 같은 JSON을 보내고 SQS 응답 뒤 완료한다`() {
        val response = CompletableFuture<SendMessageResponse>()
        `when`(client.sendMessage(request)).thenReturn(response)

        val result = publisher.publish(message)

        verify(client).sendMessage(request)
        assertThat(request.messageGroupId()).isNull()
        assertThat(request.messageDeduplicationId()).isNull()
        assertThat(result).isNotDone()
        response.complete(SendMessageResponse.builder().messageId("sqs-message").build())
        assertThat(result.join()).isEqualTo(Unit)
    }

    @Test
    fun `SDK 비동기 실패는 예외 완료한다`() {
        val failure = IllegalStateException("SQS unavailable")
        `when`(client.sendMessage(request)).thenReturn(CompletableFuture.failedFuture(failure))
        assertThatThrownBy { publisher.publish(message).join() }.hasCause(failure)
    }

    @Test
    fun `SDK 호출 자체가 던진 예외도 예외 완료한다`() {
        val failure = IllegalStateException("client closed")
        `when`(client.sendMessage(request)).thenThrow(failure)
        val result = publisher.publish(message)
        assertThatThrownBy { result.join() }.hasCause(failure)
    }

    @Test
    fun `현재 추적 컨텍스트를 traceparent 메시지 속성으로 싣는다`() {
        val traced = SqsPushPublisher(client, mapper, queueUrl) { sink -> sink("traceparent", "00-trace-span-01") }
        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        `when`(client.sendMessage(captor.capture()))
            .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()))

        traced.publish(message).join()

        val attributes = captor.value.messageAttributes()
        assertThat(attributes["traceparent"]?.stringValue()).isEqualTo("00-trace-span-01")
        assertThat(attributes["traceparent"]?.dataType()).isEqualTo("String")
        assertThat(captor.value.messageBody()).isEqualTo(request.messageBody())
    }

    @Test
    fun `추적 컨텍스트가 없으면 메시지 속성을 붙이지 않는다`() {
        `when`(client.sendMessage(request)).thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()))
        publisher.publish(message).join()
        verify(client).sendMessage(request)
    }
}
