package com.knk.manyak.admin.infra

import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.stereotype.Component

/** 서버가 쓰는 Redis 연결로 INFO와 DBSIZE를 읽는다(ADMIN 4-1-21). 조회 시각의 현재 값이라 기간과 무관하다. 시간 한도는 호출하는 쪽이 건다. */
@Component
class RedisInfraClient(private val factory: RedisConnectionFactory) {
    fun fetch(): InfraRedisMetrics = factory.connection.use { connection ->
        val commands = connection.serverCommands()
        val info = checkNotNull(commands.info())
        val keys = checkNotNull(commands.dbSize())
        fun number(name: String) = checkNotNull(info.getProperty(name)?.trim()?.toLongOrNull())
        InfraRedisMetrics(number("used_memory"), number("maxmemory"), number("connected_clients"), keys,
            if (info.getProperty("aof_enabled")?.trim() == "1") info.getProperty("aof_last_write_status")?.trim() == "ok" else null)
    }
}
