package com.knk.manyak.push.service

import com.knk.manyak.push.entity.PushMessageTemplate
import com.knk.manyak.push.repository.PushMessageTemplateRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 푸시 문구 해석(KNK-1116): 유효한 오버라이드 행이 있으면 그 값, 없거나 만료됐으면 yml 기본 문구.
 * `credit_policies`(KNK-1056)와 같은 읽기 규칙이며, 만료는 캐시가 아니라 **읽을 때** 판정한다.
 */
class PushMessageTemplateServiceTest {

    private val repository: PushMessageTemplateRepository = mock(PushMessageTemplateRepository::class.java)
    private val now = Instant.parse("2026-09-04T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun service(): PushMessageTemplateService =
        PushMessageTemplateService(repository, DEFAULT_TITLE, DEFAULT_BODY, clock, "이프 만료 예정 안내", "곧 만료되는 이프가 있어요").also { it.refresh() }

    private fun template(title: String, body: String, from: Instant, until: Instant?) =
        PushMessageTemplate(
            templateKey = PushTemplateKey.ATTENDANCE_REMINDER.storageKey,
            title = title,
            body = body,
            effectiveFrom = from,
            effectiveUntil = until,
        )

    @Test
    fun `오버라이드가 없으면 yml 기본 문구를 쓴다`() {
        `when`(repository.findAll()).thenReturn(emptyList())

        val resolved = service().templateOf(PushTemplateKey.ATTENDANCE_REMINDER)

        assertThat(resolved.title).isEqualTo(DEFAULT_TITLE)
        assertThat(resolved.body).isEqualTo(DEFAULT_BODY)
    }

    @Test
    fun `유효한 오버라이드가 있으면 그 문구를 쓴다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(template("이벤트 제목", "이벤트 본문", from = now.minusSeconds(60), until = null)),
        )

        val resolved = service().templateOf(PushTemplateKey.ATTENDANCE_REMINDER)

        assertThat(resolved.title).isEqualTo("이벤트 제목")
        assertThat(resolved.body).isEqualTo("이벤트 본문")
    }

    @Test
    fun `만료된 오버라이드는 무시하고 기본 문구로 돌아간다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(template("끝난 이벤트", "끝난 본문", from = now.minusSeconds(600), until = now.minusSeconds(1))),
        )

        val resolved = service().templateOf(PushTemplateKey.ATTENDANCE_REMINDER)

        assertThat(resolved.title).isEqualTo(DEFAULT_TITLE)
    }

    @Test
    fun `아직 시작되지 않은 오버라이드도 무시한다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(template("예약 이벤트", "예약 본문", from = now.plusSeconds(60), until = null)),
        )

        assertThat(service().templateOf(PushTemplateKey.ATTENDANCE_REMINDER).title).isEqualTo(DEFAULT_TITLE)
    }

    @Test
    fun `유효한 행이 여럿이면 effective_from이 가장 최신인 것을 쓴다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(
                template("옛 이벤트", "옛 본문", from = now.minusSeconds(600), until = null),
                template("새 이벤트", "새 본문", from = now.minusSeconds(60), until = null),
            ),
        )

        assertThat(service().templateOf(PushTemplateKey.ATTENDANCE_REMINDER).title).isEqualTo("새 이벤트")
    }

    @Test
    fun `광고 접두는 서버가 붙이되 이미 붙어 있으면 덧붙이지 않는다`() {
        // 정보통신망법 제50조 표기는 DB 값에 맡기지 않는다. 운영자가 실수로 넣어도 "(광고) (광고) …"가 되면 안 된다.
        assertThat(withAdPrefix("출석하세요")).isEqualTo("(광고) 출석하세요")
        assertThat(withAdPrefix("(광고) 출석하세요")).isEqualTo("(광고) 출석하세요")
    }

    @Test
    fun `본문 끝 안내는 줄바꿈 하나로 붙이고 끝 공백을 제외해 중복 판정한다`() {
        val notice = "수신거부: 마이 > 알림 설정"
        assertThat(withAdOptOutNotice("출석하세요")).isEqualTo("출석하세요\n$notice")
        for (suffix in listOf("", " ", "\n\t")) {
            val body = "출석하세요\n$notice$suffix"
            assertThat(withAdOptOutNotice(body)).isEqualTo(body)
            assertThat(withAdOptOutNotice(withAdOptOutNotice(body))).isEqualTo(body)
        }
        // 안내가 중간에만 있으면 본문 끝에도 안내가 필요하다.
        assertThat(withAdOptOutNotice("$notice\n추가 소식")).isEqualTo("$notice\n추가 소식\n$notice")
    }

    private companion object {
        const val DEFAULT_TITLE = "기본 제목"
        const val DEFAULT_BODY = "기본 본문"
    }
}
