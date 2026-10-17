package com.knk.manyak.chat.service

import com.knk.manyak.global.observability.StructuredLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.mockito.Mockito
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.domain.Range
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ChatGenerationEventsTests {
    private fun withRedis(test: (StringRedisTemplate, ChatGenerationStore, ChatGenerationEvents) -> Unit) {
        val factory = LettuceConnectionFactory(
            org.springframework.data.redis.connection.RedisStandaloneConfiguration("localhost", System.getProperty("spring.data.redis.port").toInt()),
            org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build(),
        )
        factory.afterPropertiesSet()
        try {
            val redis = StringRedisTemplate(factory)
            val mapper = jacksonObjectMapper()
            val events = ChatGenerationEvents(redis, mapper, StructuredLogger())
            try { test(redis, ChatGenerationStore(redis, mapper), events) } finally { events.destroy() }
        } finally { factory.destroy() }
    }

    @Test fun `실제 Redis Streams가 모든 이벤트를 순서대로 보관하고 종료 TTL을 재설정한다`() = withRedis { redis, _, events ->
        val chat = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val writer = events.writer(chat, id, Instant.now())
        writer.write("started", mapOf("generationId" to id))
        val key = "chat:$chat:gen:$id"
        assertThat(redis.getExpire(key)).isBetween(148, 150)
        writer.write("token", mapOf("text" to "한글"))
        writer.write("character_image", mapOf("name" to "인물", "imageUrl" to "https://test/image"))
        writer.write("completed", mapOf("aiOutput" to "한글"))
        writer.write("token", mapOf("text" to "종료 뒤 토큰"))
        val received = mutableListOf<Pair<String, String>>()
        events.receive(chat, id, { true }) { name, data -> received.add(name to data) }
        assertThat(received.map { it.first }).containsExactly("started", "token", "character_image", "completed")
        assertThat(received[1].second).isEqualTo("""{"text":"한글"}""")
        assertThat(redis.getExpire(key)).isBetween(298, 300)
    }

    @Test fun `큐 대기 중 스트림 없는 첫 연결과 상세 직후 재연결은 기다리며 공유 Redis 명령을 막지 않는다`() = withRedis { redis, store, events ->
        val chat = UUID.randomUUID().toString()
        val generation = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reading = CompletableFuture.runAsync {
            events.receive(chat, generation.generationId, { true }) { name, _ -> received.add(name) }
        }
        // 실제 서버의 차단 상태를 관측한 뒤에만 공유 연결 명령을 제출한다.
        val blocked = redis.connectionFactory!!.connection.use { observer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var clientId: String? = null
            while (clientId == null && System.nanoTime() < deadline) {
                val clients = observer.execute("CLIENT", "LIST".toByteArray()) as String
                clientId = clients.lineSequence().map { line ->
                    line.split(" ").mapNotNull { field ->
                        field.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                    }.toMap()
                }.firstOrNull { it["cmd"] == "xread" && it["flags"]?.contains("b") == true }?.get("id")
                if (clientId == null) Thread.sleep(10)
            }
            requireNotNull(clientId) { "Redis에서 XREAD BLOCK 진입을 확인하지 못했다" }
        }
        val shared = CompletableFuture.runAsync {
            redis.opsForValue().set("test:stream:$chat", "ok")
            assertThat(redis.opsForValue().get("test:stream:$chat")).isEqualTo("ok")
        }
        shared.get(1, TimeUnit.SECONDS)
        redis.connectionFactory!!.connection.use { observer ->
            val clients = observer.execute("CLIENT", "LIST".toByteArray()) as String
            assertThat(clients.lineSequence().any {
                it.startsWith("id=$blocked ") && it.contains("cmd=xread") &&
                    it.split(" ").any { field -> field.startsWith("flags=") && field.substringAfter("=").contains("b") }
            }).isTrue() // 공유 명령 완료 시에도 같은 XREAD가 아직 BLOCK 중이다.
        }
        assertThat(reading.isDone).isFalse()
        val writer = events.writer(chat, generation.generationId, generation.startedAt)
        writer.write("started", mapOf("generationId" to generation.generationId))
        writer.write("error", mapOf("code" to "TEST"))
        reading.get(8, TimeUnit.SECONDS)
        assertThat(received).containsExactly("started", "error")
    }

    @Test fun `종료 이벤트 없이 대기하다 생성 중 키가 교체되면 5초 확인 뒤 닫는다`() = withRedis { _, store, events ->
        val chat = UUID.randomUUID().toString()
        val generation = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
        events.writer(chat, generation.generationId, generation.startedAt).write("started", emptyMap<String, String>())
        val started = java.util.concurrent.CountDownLatch(1)
        val reading = CompletableFuture.runAsync {
            events.receive(chat, generation.generationId, { true }) { _, _ -> started.countDown() }
        }
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(reading.isDone).isFalse()
        store.release(chat, generation.generationId)
        store.acquire(chat, "CONTINUE", "다음", Instant.now(), null)
        reading.get(8, TimeUnit.SECONDS)
    }

    @Test fun `빈 XREAD 뒤 키 확인 사이에 기록된 여러 배치와 completed를 모두 전달한다`() = withRedis { redis, store, _ ->
        val chat = UUID.randomUUID().toString()
        val generation = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
        val spy = Mockito.spy(redis)
        val values = Mockito.spy(redis.opsForValue())
        val events = ChatGenerationEvents(spy, jacksonObjectMapper(), StructuredLogger())
        try {
            val writer = events.writer(chat, generation.generationId, generation.startedAt)
            writer.write("started", mapOf("generationId" to generation.generationId))
            var injected = false
            Mockito.doAnswer { call ->
                // GET은 빈 XREAD 뒤에만 호출된다. 키를 읽기 직전에 정상 종료 순서를 강제한다.
                if (!injected) {
                    injected = true
                    repeat(205) { writer.write("token", mapOf("text" to "토큰 $it")) }
                    writer.write("character_image", mapOf("name" to "인물", "imageUrl" to "https://test/image"))
                    writer.write("completed", mapOf("aiOutput" to "저장됨"))
                    writer.finish()
                    store.release(chat, generation.generationId)
                }
                call.callRealMethod()
            }.`when`(values).get("chat:$chat:generating")
            Mockito.doReturn(values).`when`(spy).opsForValue()
            val received = mutableListOf<Pair<String, String>>()
            events.receive(chat, generation.generationId, { true }) { name, data -> received.add(name to data) }
            assertThat(injected).isTrue()
            assertThat(received.map { it.first }).containsExactlyElementsOf(
                listOf("started") + List(205) { "token" } + listOf("character_image", "completed"),
            )
            assertThat(received.filter { it.first == "token" }.map { it.second }).containsExactlyElementsOf(
                List(205) { """{"text":"토큰 $it"}""" },
            )
        } finally { events.destroy() }
    }

    @Test fun `미종료 스트림은 최초 TTL을 연장하지 않고 만료 뒤 저장 완료 이벤트로 되살리지 않는다`() = withRedis { redis, _, events ->
        val chat = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val startedAt = Instant.now().minusSeconds(148)
        val writer = events.writer(chat, id, startedAt)
        val key = "chat:$chat:gen:$id"
        writer.write("started", emptyMap<String, String>())
        redis.expire(key, Duration.ofSeconds(30))
        writer.write("token", mapOf("text" to "계속"))
        assertThat(redis.getExpire(key)).isBetween(28, 30)
        redis.expire(key, Duration.ZERO) // 장시간 저장 중 TTL 만료와 동일한 Redis 상태
        waitPastCreationDeadline(startedAt)
        writer.write("completed", mapOf("aiOutput" to "저장됨"))
        writer.finish()
        assertThat(redis.hasKey(key)).isFalse()
        events.receive(chat, id, { true }) { _, _ -> error("만료 스트림은 재생할 수 없다") }
    }

    @Test fun `완료 뒤 두 재연결은 독립적인 읽기 위치로 처음부터 종료까지 재생한다`() = withRedis { _, _, events ->
        val chat = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val writer = events.writer(chat, id, Instant.now())
        writer.write("started", mapOf("generationId" to id))
        writer.write("token", mapOf("text" to "본문"))
        writer.write("error", mapOf("code" to "AI_STREAM_FAILED"))
        repeat(2) {
            val received = mutableListOf<String>()
            events.receive(chat, id, { true }) { name, _ -> received.add(name) }
            assertThat(received).containsExactly("started", "token", "error")
        }
    }
    @Test fun `종료 이벤트가 없어도 해당 키가 남아 있으면 5초 대기 뒤 계속 수신하고 키와 스트림 만료 뒤 닫는다`() = withRedis { redis, store, _ ->
        val chat = UUID.randomUUID().toString()
        val generation = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
        val spy = org.mockito.Mockito.spy(redis)
        val checked = java.util.concurrent.CountDownLatch(1)
        val values = org.mockito.Mockito.spy(redis.opsForValue())
        org.mockito.Mockito.doAnswer { call ->
            val result = call.callRealMethod()
            checked.countDown()
            result
        }.`when`(values).get("chat:$chat:generating")
        org.mockito.Mockito.doReturn(values).`when`(spy).opsForValue()
        val events = ChatGenerationEvents(spy, jacksonObjectMapper(), StructuredLogger())
        try {
            events.writer(chat, generation.generationId, generation.startedAt).write("started", emptyMap<String, String>())
            val received = java.util.Collections.synchronizedList(mutableListOf<String>())
            val reading = CompletableFuture.runAsync {
                events.receive(chat, generation.generationId, { true }) { name, _ -> received.add(name) }
            }
            assertThat(checked.await(8, TimeUnit.SECONDS)).isTrue()
            assertThat(reading.isDone).isFalse()
            // 강제 종료 뒤 두 Redis 키가 TTL로 사라진 상태를 재현한다.
            redis.expire("chat:$chat:gen:${generation.generationId}", Duration.ofMillis(1))
            redis.expire("chat:$chat:generating", Duration.ofMillis(1))
            reading.get(8, TimeUnit.SECONDS)
            assertThat(received).containsExactly("started")
            assertThat(redis.hasKey("chat:$chat:gen:${generation.generationId}")).isFalse()
        } finally { events.destroy() }
    }

    @Test fun `토큰과 인물 이미지 뒤 재연결은 첫 이벤트부터 재생하고 새 토큰과 완료를 이어받는다`() = withRedis { _, store, events ->
        val chat = UUID.randomUUID().toString()
        val generation = store.acquire(chat, "CONTINUE", "입력", Instant.now(), null)
        val writer = events.writer(chat, generation.generationId, generation.startedAt)
        writer.write("started", mapOf("generationId" to generation.generationId))
        writer.write("token", mapOf("text" to "앞부분"))
        writer.write("character_image", mapOf("name" to "인물", "imageUrl" to "https://test/image"))
        val caughtUp = java.util.concurrent.CountDownLatch(1)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reading = CompletableFuture.runAsync {
            events.receive(chat, generation.generationId, { true }) { name, _ ->
                received.add(name)
                if (name == "character_image") caughtUp.countDown()
            }
        }
        assertThat(caughtUp.await(3, TimeUnit.SECONDS)).isTrue()
        writer.write("token", mapOf("text" to "뒷부분"))
        writer.write("completed", mapOf("aiOutput" to "확정 본문"))
        reading.get(3, TimeUnit.SECONDS)
        assertThat(received).containsExactly("started", "token", "character_image", "token", "completed")
    }

    @Test fun `신규 생성 기한이 지나도 이미 있는 스트림에는 완료를 추가하고 종료 TTL을 적용한다`() = withRedis { redis, _, events ->
        val chat = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val key = "chat:$chat:gen:$id"
        redis.opsForStream<String, String>().add(key, mapOf("event" to "started", "data" to "{}"))
        redis.expire(key, Duration.ofSeconds(30))
        val writer = events.writer(chat, id, Instant.now().minusSeconds(151))
        writer.write("completed", mapOf("aiOutput" to "저장됨"))
        assertThat(redis.opsForStream<String, String>().range(key, Range.unbounded())!!.map { it.value["event"] }).containsExactly("started", "completed")
        assertThat(redis.getExpire(key)).isBetween(298, 300)
    }

    private fun waitPastCreationDeadline(startedAt: Instant) {
        val remaining = Duration.between(Instant.now(), startedAt.plusSeconds(ChatGenerationEvents.INITIAL_TTL_SECONDS)).toMillis()
        if (remaining >= 0) Thread.sleep(remaining + 20)
    }

    @Test fun `최초 Lua 성공 후 응답 유실과 만료가 겹쳐도 기한 지난 completed가 스트림을 재생성하지 않는다`() = withRedis { redis, _, _ ->
        val lostReply = Mockito.spy(redis)
        var first = true
        Mockito.doAnswer { call ->
            val result = call.callRealMethod()
            if (first) {
                first = false
                throw RedisSystemException("Lua 적용 뒤 응답 유실", java.util.concurrent.TimeoutException())
            }
            result
        }.`when`(lostReply).execute(
            Mockito.any<RedisScript<Long>>(), Mockito.anyList(),
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
        )
        val events = ChatGenerationEvents(lostReply, jacksonObjectMapper(), StructuredLogger())
        try {
            val chat = UUID.randomUUID().toString()
            val id = UUID.randomUUID().toString()
            val key = "chat:$chat:gen:$id"
            val startedAt = Instant.now().minusSeconds(148)
            val writer = events.writer(chat, id, startedAt)
            writer.write("started", mapOf("generationId" to id))
            assertThat(redis.hasKey(key)).isTrue()
            assertThat(redis.opsForStream<String, String>().range(key, Range.unbounded())!!.map { it.value["event"] }).containsExactly("started")
            redis.expire(key, Duration.ZERO)
            assertThat(redis.hasKey(key)).isFalse()
            waitPastCreationDeadline(startedAt)
            writer.write("completed", mapOf("aiOutput" to "늦은 저장"))
            assertThat(redis.hasKey(key)).isFalse()
        } finally { events.destroy() }
    }

    @Test fun `최초 기록이 실행 전에 실패하면 기한 안의 다음 기록이 스트림을 만든다`() = withRedis { redis, _, _ ->
        val failedFirst = Mockito.spy(redis)
        var first = true
        Mockito.doAnswer { call ->
            if (first) {
                first = false
                throw RedisSystemException("Lua 실행 전 실패", java.util.concurrent.TimeoutException())
            }
            call.callRealMethod()
        }.`when`(failedFirst).execute(
            Mockito.any<RedisScript<Long>>(), Mockito.anyList(),
            Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
        )
        val events = ChatGenerationEvents(failedFirst, jacksonObjectMapper(), StructuredLogger())
        try {
            val chat = UUID.randomUUID().toString()
            val id = UUID.randomUUID().toString()
            val key = "chat:$chat:gen:$id"
            val writer = events.writer(chat, id, Instant.now())
            writer.write("started", mapOf("generationId" to id))
            assertThat(redis.hasKey(key)).isFalse()
            writer.write("token", mapOf("text" to "복구"))
            assertThat(redis.opsForStream<String, String>().range(key, Range.unbounded())!!.map { it.value["event"] }).containsExactly("token")
            assertThat(redis.getExpire(key)).isBetween(148, 150)
        } finally { events.destroy() }
    }
}
