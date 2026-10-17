package com.knk.manyak.chat.service

import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.beans.factory.DisposableBean
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/** 생성 작업만 기록한다. 수신 연결의 종료와 기록 실패는 생성·저장·정산에 영향을 주지 않는다. */
@Component
class ChatGenerationEvents(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val logger: StructuredLogger,
) : DisposableBean {
    // 운영 공유 명령 한도(2초)는 XREAD BLOCK 5초보다 짧다. 읽기용 팩토리만 10초로 분리한다.
    // 기존 호스트·인증·TLS·클라이언트 리소스 설정을 그대로 사용하며 Spring 빈으로 노출하지 않는다.
    private val readFactory = lazy {
        val source = requireNotNull(redis.connectionFactory) as LettuceConnectionFactory
        val client = object : LettuceClientConfiguration by source.clientConfiguration {
            override fun getCommandTimeout(): Duration = Duration.ofSeconds(READ_COMMAND_TIMEOUT_SECONDS)
        }
        val factory = when {
            source.clusterConfiguration != null -> LettuceConnectionFactory(requireNotNull(source.clusterConfiguration), client)
            source.sentinelConfiguration != null -> LettuceConnectionFactory(requireNotNull(source.sentinelConfiguration), client)
            else -> LettuceConnectionFactory(requireNotNull(source.standaloneConfiguration), client)
        }
        factory.shareNativeConnection = false
        factory.afterPropertiesSet()
        factory.start()
        factory
    }

    override fun destroy() {
        if (readFactory.isInitialized()) readFactory.value.destroy()
    }

    fun writer(chatId: String, generationId: String, startedAt: Instant) = Writer(chatId, generationId, startedAt)
    fun exists(chatId: String, generationId: String) = redis.hasKey(key(chatId, generationId))

    inner class Writer(private val chatId: String, generationId: String, startedAt: Instant) {
        private val streamKey = key(chatId, generationId)
        private val createBeforeMillis = startedAt.plusSeconds(INITIAL_TTL_SECONDS).toEpochMilli()
        private var ended = false
        private var failureLogged = false

        // 마감 스레드와 AI 콜백이 겹쳐도 종료 이벤트 뒤에 토큰이 기록되지 않도록 직렬화한다.
        @Synchronized fun write(name: String, body: Any) {
            if (ended) return
            val terminal = name == "completed" || name == "error"
            if (terminal) ended = true
            quietly {
                redis.execute(APPEND, listOf(streamKey), name, mapper.writeValueAsString(body),
                    createBeforeMillis.toString(), if (terminal) RETENTION_SECONDS.toString() else "0")
            }
        }

        @Synchronized fun finish() {
            ended = true
            quietly { redis.expire(streamKey, Duration.ofSeconds(RETENTION_SECONDS)) }
        }

        private fun quietly(action: () -> Unit) {
            try { action() } catch (ex: Exception) {
                if (!failureLogged) {
                    failureLogged = true
                    logger.event("chat_generation_event_write_failed", "chat_id" to chatId, "error" to ex.javaClass.simpleName)
                }
            }
        }
    }

    /** 한 수신 루프 동안 같은 LettuceConnection을 유지해 전용 TCP 연결을 재사용한다. */
    fun receive(chatId: String, generationId: String, connected: () -> Boolean, deliver: (String, String) -> Unit) {
        val streamKey = key(chatId, generationId).toByteArray(Charsets.UTF_8)
        var offset = "0-0"
        var draining = false
        readFactory.value.connection.use { connection ->
            while (connected()) {
                val options = StreamReadOptions.empty().count(100)
                val records = connection.streamCommands().xRead(
                    if (draining) options else options.block(Duration.ofSeconds(READ_WAIT_SECONDS)),
                    StreamOffset.create(streamKey, ReadOffset.from(offset)),
                ).orEmpty()
                for (record in records) {
                    if (!connected()) return
                    val fields = record.value.entries.associate { (k, v) -> k.toString(Charsets.UTF_8) to v.toString(Charsets.UTF_8) }
                    val name = requireNotNull(fields["event"])
                    deliver(name, requireNotNull(fields["data"]))
                    offset = record.id.value
                    if (name == "completed" || name == "error") return
                }
                if (records.isEmpty()) {
                    if (draining) return
                    val value = redis.opsForValue().get("chat:$chatId:generating")
                    // 종료 이벤트 기록 → 키 해제 사이에 빈 XREAD가 반환됐을 수 있다.
                    // 키 불일치 뒤에는 마지막 offset부터 비블로킹으로 모든 배치를 비운다.
                    draining = value == null || mapper.readTree(value)["generationId"].asString() != generationId
                }
            }
        }
    }

    companion object {
        const val INITIAL_TTL_SECONDS = 150L
        const val RETENTION_SECONDS = 300L
        const val READ_WAIT_SECONDS = 5L
        const val READ_COMMAND_TIMEOUT_SECONDS = 10L
        private fun key(chatId: String, generationId: String) = "chat:$chatId:gen:$generationId"
        // 최초 응답 성공 여부와 무관하게 생성 시작+150초를 Redis 시각으로 판정한다.
        // 기한 뒤에는 기존 스트림에만 추가하고, XADD와 TTL도 원자적으로 적용한다.
        private val APPEND = DefaultRedisScript<Long>(
            """local exists = redis.call('EXISTS', KEYS[1])
                if exists == 0 then
                    local now = redis.call('TIME')
                    local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                    if nowMillis >= tonumber(ARGV[3]) then return 0 end
                end
                redis.call('XADD', KEYS[1], '*', 'event', ARGV[1], 'data', ARGV[2])
                if exists == 0 then redis.call('EXPIRE', KEYS[1], $INITIAL_TTL_SECONDS) end
                if ARGV[4] ~= '0' then redis.call('EXPIRE', KEYS[1], ARGV[4]) end
                return 1""".trimIndent(), Long::class.java,
        )
    }
}
