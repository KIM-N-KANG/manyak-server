package com.knk.manyak.support

import com.knk.manyak.chat.service.ChatGenerationStore
import org.awaitility.Awaitility.await
import java.time.Duration

fun ChatGenerationStore.awaitGenerationReleased(chatId: String) {
    // completed 수신은 워커 finally의 생성 키 해제까지 보장하지 않는다(CHAT 5-2-6).
    await().alias("채팅 $chatId 생성 키 해제").atMost(Duration.ofSeconds(10))
        .until { get(chatId) == null }
}
