package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.GeneratingTurnResponse
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ChatGenerationStore(private val redis: StringRedisTemplate, private val mapper: ObjectMapper) {
    fun acquire(chatId: String, type: String, userInput: String, startedAt: Instant, targetTurnId: Long?): GeneratingTurnResponse {
        val generation = GeneratingTurnResponse(type, userInput, startedAt, targetTurnId, UUID.randomUUID().toString())
        val acquired = try {
            redis.opsForValue().setIfAbsent(key(chatId), mapper.writeValueAsString(generation), Duration.ofSeconds(TTL_SECONDS))
                ?: throw IllegalStateException("Redis SET NX returned no result")
        } catch (ex: Exception) {
            throw CodedResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "생성 상태를 확인할 수 없습니다.", ex)
        }
        if (!acquired) throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.CHAT_TURN_IN_PROGRESS, "이미 생성 중인 턴이 있습니다.")
        return generation
    }

    fun get(chatId: String): GeneratingTurnResponse? = redis.opsForValue().get(key(chatId))
        ?.let { mapper.readValue(it, GeneratingTurnResponse::class.java) }

    // 만료 뒤 다른 요청이 획득한 키는 건드리지 않는다. 비교와 삭제는 같은 Redis 명령 안에서 수행한다.
    fun release(chatId: String, generationId: String) {
        redis.execute(RELEASE, listOf(key(chatId)), generationId)
    }

    companion object {
        const val TTL_SECONDS = 150L
        private fun key(chatId: String) = "chat:$chatId:generating"
        private val RELEASE = DefaultRedisScript<Long>(
            """local value = redis.call('GET', KEYS[1])
                if value and cjson.decode(value).generationId == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0""".trimIndent(), Long::class.java,
        )
    }
}
