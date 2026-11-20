package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisServerCommands
import java.util.Properties

/** ADMIN 5-5-13 'Redis 값', 'AOF 꺼짐'. 서버가 쓰는 Redis 연결로 INFO와 DBSIZE만 부른다. */
class RedisInfraClientTests {
    private fun client(info: Map<String, String>, keys: Long = 7450): Pair<RedisInfraClient, RedisServerCommands> {
        val factory = mock(RedisConnectionFactory::class.java); val connection = mock(RedisConnection::class.java)
        val commands = mock(RedisServerCommands::class.java)
        `when`(factory.connection).thenReturn(connection); `when`(connection.serverCommands()).thenReturn(commands)
        `when`(commands.info()).thenReturn(Properties().apply { putAll(info) }); `when`(commands.dbSize()).thenReturn(keys)
        return RedisInfraClient(factory) to commands
    }

    @Test fun `'Redis 값' INFO와 DBSIZE의 다섯 값을 읽고 AOF 쓰기 ok면 true`() {
        val (client, commands) = client(mapOf("used_memory" to "2474639", "maxmemory" to "268435456", "connected_clients" to "6",
            "aof_enabled" to "1", "aof_last_write_status" to "ok"))
        assertThat(client.fetch()).isEqualTo(InfraRedisMetrics(2474639, 268435456, 6, 7450, true))
        verify(commands).info(); verify(commands).dbSize(); verifyNoMoreInteractions(commands)
    }

    @Test fun `'AOF 꺼짐' aof_enabled 0이면 null이고 마지막 쓰기 실패면 false`() {
        val off = mapOf("used_memory" to "1", "maxmemory" to "0", "connected_clients" to "1", "aof_enabled" to "0", "aof_last_write_status" to "ok")
        assertThat(client(off).first.fetch().aofLastWriteOk).isNull()
        assertThat(client(off + ("aof_enabled" to "1") + ("aof_last_write_status" to "err")).first.fetch().aofLastWriteOk).isFalse()
    }

    @Test fun `필수 값이 없으면 실패로 본다`() {
        assertThatThrownBy { client(mapOf("used_memory" to "1")).first.fetch() }.isInstanceOf(IllegalStateException::class.java)
    }
}
