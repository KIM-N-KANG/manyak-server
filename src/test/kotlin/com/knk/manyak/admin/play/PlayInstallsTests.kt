package com.knk.manyak.admin.play

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.time.*
import java.util.concurrent.*
import java.util.zip.GZIPOutputStream

internal fun playRow(date: String, installs: Long, uninstalls: Long = 0, active: Long = 11) =
    "$date,app.manyak,$installs,$uninstalls,0,999999,0,0,$active,0,0,0"
internal fun playCsv(vararg rows: String, gzip: Boolean = true): ByteArray {
    val header = "Date,Package name,Daily Device Installs,Daily Device Uninstalls,Daily Device Upgrades,Total User Installs,Daily User Installs,Daily User Uninstalls,Active Device Installs,Install events,Update events,Uninstall events"
    val bytes = ("\uFEFF" + header + "\r\n" + rows.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_16LE)
    if (!gzip) return bytes
    return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
}

class PlayInstallsTests {
    private val parser = PlayInstallCsv()
    @Test fun `gzip UTF16 BOM과 따옴표를 읽고 월별 합산 및 누락 날짜를 보간한다`() {
        val report = parser.parse(listOf(playCsv(playRow("2026-10-02", 2, 1)), playCsv(),
            playCsv(playRow("2026-09-30", 29, active = 10).replace("app.manyak", "\"app.manyak\""), gzip = false)), "app.manyak")
        assertThat(report.totalInstalls).isEqualTo(31); assertThat(report.activeDevices).isEqualTo(11)
        assertThat(report.asOf).isEqualTo(LocalDate.parse("2026-10-02"))
        assertThat(report.daily.map { it.date }).containsExactly(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))
        assertThat(report.daily.map { it.cumulativeInstalls }).containsExactly(29L, 29L, 31L)
        assertThat(report.daily[1].installs).isZero(); assertThat(report.daily[1].uninstalls).isZero(); assertThat(report.daily[1].activeDevices).isNull()
        assertThat(report.daily.last().uninstalls).isEqualTo(1)
    }
    @Test fun `파일 없음 중복 날짜 음수 잘못된 날짜와 패키지 및 인코딩을 거부한다`() {
        val row = playRow("2026-10-02", 1)
        for (files in listOf(emptyList(), listOf(playCsv()), listOf(playCsv(row, row)), listOf(playCsv(playRow("2026-10-02", -1))),
            listOf(playCsv(playRow("2026-02-30", 1))), listOf(playCsv(row.replace("app.manyak", "other.app"))),
            listOf(byteArrayOf(1)), listOf("bad headers".toByteArray(Charsets.UTF_16LE)), listOf(playCsv(row.replace("app.manyak", "\"app.manyak"))))) {
            assertThatThrownBy { parser.parse(files, "app.manyak") }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { parser.parse(listOf(playCsv(playRow("2026-10-01", Long.MAX_VALUE), playRow("2026-10-02", 1))), "app.manyak") }
            .isInstanceOf(ArithmeticException::class.java)
    }
    @Test fun `1시간 캐시와 실패 완료 뒤 60초 쿨다운 및 복구를 지킨다`() {
        val clock = PlayClock(Instant.parse("2026-10-22T00:00:00Z")); var calls = 0; var fail = false
        val source = PlayReportsSource { calls++; if (fail) throw PlayStatsFetchFailure("http_error"); listOf(playCsv(playRow("2026-10-17", 31))) }
        val service = AdminPlayInstallsService(source, parser, PlayStatsProperties(), clock)
        val cached = service.dashboard(); clock.at = clock.at.plusSeconds(3599)
        assertThat(service.dashboard()).isEqualTo(cached); assertThat(calls).isEqualTo(1)
        clock.at = clock.at.plusSeconds(1); fail = true
        assertThat(service.dashboard()).isEqualTo(cached); assertThat(calls).isEqualTo(2)
        clock.at = clock.at.plusSeconds(59); assertThat(service.dashboard()).isEqualTo(cached); assertThat(calls).isEqualTo(2)
        clock.at = clock.at.plusSeconds(1); fail = false; service.dashboard(); assertThat(calls).isEqualTo(3)
    }
    @Test fun `첫 실패와 해석 실패는 503 대상이며 쿨다운에서 재호출하지 않는다`() {
        val clock = PlayClock(Instant.parse("2026-10-22T00:00:00Z")); var calls = 0
        val service = AdminPlayInstallsService(PlayReportsSource { calls++; emptyList() }, parser, PlayStatsProperties(), clock)
        repeat(2) { assertThatThrownBy { service.dashboard() }.isInstanceOf(PlayStatsUnavailable::class.java) }
        assertThat(calls).isEqualTo(1)
    }
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `동시 갱신 중 다른 요청은 기다리지 않고 이전 값 또는 사용 불가를 받는다`(hasCache: Boolean) {
        val clock = PlayClock(Instant.parse("2026-10-22T00:00:00Z")); var blocking = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = java.util.concurrent.atomic.AtomicInteger()
        val service = AdminPlayInstallsService(PlayReportsSource {
            calls.incrementAndGet(); if (blocking) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }; listOf(playCsv(playRow("2026-10-17", 31)))
        }, parser, PlayStatsProperties(), clock)
        val previous = if (hasCache) service.dashboard() else null
        clock.at = clock.at.plusSeconds(3600); blocking = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<PlayInstallsResponse> { service.dashboard() }
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            if (previous == null) assertThatThrownBy { service.dashboard() }.isInstanceOf(PlayStatsUnavailable::class.java)
            else assertThat(service.dashboard()).isEqualTo(previous)
            assertThat(calls.get()).isEqualTo(if (hasCache) 2 else 1); release.countDown(); assertThat(first.get(2, TimeUnit.SECONDS).totalInstalls).isEqualTo(31)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}

class PlayClock(@Volatile var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
