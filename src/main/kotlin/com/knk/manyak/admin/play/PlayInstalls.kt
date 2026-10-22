package com.knk.manyak.admin.play

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("manyak.admin.play-stats")
class PlayStatsProperties(
    val bucket: String = "pubsite_prod_7346202752431827582",
    val packageName: String = "app.manyak",
    val serviceAccountKeyBase64: String = "",
    val storageBaseUrl: String = "https://storage.googleapis.com",
)

data class PlayInstallDay(val date: LocalDate, val installs: Long, val uninstalls: Long, val cumulativeInstalls: Long, val activeDevices: Long?)
data class PlayInstallsResponse(val totalInstalls: Long, val activeDevices: Long, val asOf: LocalDate, val daily: List<PlayInstallDay>)
fun interface PlayReportsSource { fun reports(): List<ByteArray> }
class PlayStatsFetchFailure(val reason: String, val status: Int? = null) : RuntimeException("Play reports request failed")
class PlayStatsUnavailable : RuntimeException("설치 지표를 가져올 수 없습니다.")

@Component
class PlayInstallCsv {
    private data class Day(val installs: Long, val uninstalls: Long, val active: Long)

    fun parse(files: List<ByteArray>, packageName: String): PlayInstallsResponse {
        val days = sortedMapOf<LocalDate, Day>()
        require(files.isNotEmpty())
        for (file in files) {
            val records = try {
                val bytes = if (file.size >= 2 && file[0] == 0x1f.toByte() && file[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(file.inputStream()).use { it.readBytes() }
                } else file
                val text = Charsets.UTF_16LE.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                records(text)
            } catch (_: Exception) { throw IllegalArgumentException("Invalid Play report encoding or CSV") }
            require(records.isNotEmpty())
            val header = records.first().map { it.trim() }
            require(header.distinct().size == header.size)
            val columns = listOf("Date", "Package name", "Daily Device Installs", "Daily Device Uninstalls", "Active Device Installs").associateWith {
                header.indexOf(it).also { index -> require(index >= 0) }
            }
            for (row in records.drop(1)) {
                require(row.size == header.size)
                fun value(key: String) = row[columns.getValue(key)].trim()
                fun number(key: String): Long = value(key).toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("Invalid Play report metric")
                require(value("Package name") == packageName)
                require(value("Date").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                val date = try { LocalDate.parse(value("Date")) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid Play report date") }
                require(days.put(date, Day(number("Daily Device Installs"), number("Daily Device Uninstalls"), number("Active Device Installs"))) == null)
            }
        }
        require(days.isNotEmpty())
        var cumulative = 0L
        val daily = generateSequence(days.firstKey()) { it.plusDays(1) }.takeWhile { it <= days.lastKey() }.map { date ->
            val row = days[date]
            cumulative = Math.addExact(cumulative, row?.installs ?: 0)
            PlayInstallDay(date, row?.installs ?: 0, row?.uninstalls ?: 0, cumulative, row?.active)
        }.toList()
        return PlayInstallsResponse(cumulative, days.getValue(days.lastKey()).active, days.lastKey(), daily)
    }

    /** 따옴표 안의 개행과 이중 따옴표를 포함한 CSV를 레코드 단위로 읽는다. */
    private fun records(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closedQuote = false; var i = 0
        fun cell() { row += field.toString(); field.setLength(0); closedQuote = false }
        fun line() { cell(); if (row.any { it.isNotBlank() }) rows += row.toList(); row.clear() }
        while (i < text.length) {
            val char = text[i]
            if (quoted) {
                if (char == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; closedQuote = true }
                } else field.append(char)
            } else when (char) {
                '"' -> { require(field.isEmpty() && !closedQuote); quoted = true }
                ',' -> cell()
                '\r', '\n' -> { line(); if (char == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> { require(!closedQuote); field.append(char) }
            }
            i++
        }
        require(!quoted)
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) line()
        return rows
    }
}

@Service
@EnableConfigurationProperties(PlayStatsProperties::class)
class AdminPlayInstallsService(
    private val source: PlayReportsSource, private val parser: PlayInstallCsv, private val properties: PlayStatsProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Cache(val loadedAt: Instant, val response: PlayInstallsResponse)
    @Volatile private var cached: Cache? = null
    @Volatile private var retryAfter: Instant = Instant.MIN
    private val refreshing = AtomicBoolean()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun dashboard(): PlayInstallsResponse {
        fun fresh(value: Cache?) = value != null && clock.instant().isBefore(value.loadedAt.plusSeconds(3600))
        fun fallback() = cached?.response ?: throw PlayStatsUnavailable()
        if (fresh(cached)) return cached!!.response
        if (clock.instant().isBefore(retryAfter) || !refreshing.compareAndSet(false, true)) return fallback()
        try {
            if (fresh(cached) || clock.instant().isBefore(retryAfter)) return fallback()
            val response = parser.parse(source.reports(), properties.packageName)
            cached = Cache(clock.instant(), response)
            retryAfter = Instant.MIN
            return response
        } catch (failure: Exception) {
            retryAfter = clock.instant().plusSeconds(60)
            val external = failure as? PlayStatsFetchFailure
            log.warn("admin_play_stats_fetch_failed reason={} status={}", external?.reason ?: "parse_error", external?.status)
            return fallback()
        } finally { refreshing.set(false) }
    }
}
