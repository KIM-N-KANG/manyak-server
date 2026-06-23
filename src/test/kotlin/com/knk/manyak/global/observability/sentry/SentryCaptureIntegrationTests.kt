package com.knk.manyak.global.observability.sentry

import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실제 요청 흐름에서 Sentry 전송 정책을 검증한다.
 * GlobalExceptionHandler가 모든 예외를 처리하므로 Sentry의 자동 MVC resolver는 동작하지 않아야 하고,
 * 예상 가능한 4xx는 (자동·명시 어느 경로로도) 전송되지 않아야 한다.
 *
 * beforeSend로 이벤트를 가로채 수집하고 실제 전송은 차단한다(DSN은 더미).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["sentry.dsn=http://test@localhost/1"],
)
@Import(SentryCaptureIntegrationTests.CaptureConfig::class)
class SentryCaptureIntegrationTests {

    @TestConfiguration
    class CaptureConfig {
        @Bean
        fun capturedEvents(): CopyOnWriteArrayList<SentryEvent> = CopyOnWriteArrayList()

        @Bean
        fun captureOptions(
            capturedEvents: CopyOnWriteArrayList<SentryEvent>,
        ): Sentry.OptionsConfiguration<SentryOptions> =
            Sentry.OptionsConfiguration { options ->
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                    capturedEvents.add(event)
                    null // 수집만 하고 실제 전송은 막는다
                }
            }
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var capturedEvents: CopyOnWriteArrayList<SentryEvent>

    @BeforeEach
    fun setUp() {
        capturedEvents.clear()
    }

    @Test
    fun `예상 가능한 4xx(404)는 Sentry로 전송되지 않는다`() {
        // 셋업이 실제로 캡처를 수집하는지 먼저 확인한다(probe).
        Sentry.captureMessage("probe")
        assertThat(capturedEvents).hasSize(1)
        capturedEvents.clear()

        // 존재하지 않는 채팅 → 404. 자동 resolver/명시 캡처 어느 경로로도 전송되지 않아야 한다.
        restTestClient.post()
            .uri("/api/v1/chats/999999/turns/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
            .body("""{"userInput":"손을 올린다."}""")
            .exchange()
            .expectStatus().isNotFound

        assertThat(capturedEvents).isEmpty()
    }
}
