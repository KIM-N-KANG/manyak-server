package com.knk.manyak.support

import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.push.outbox.PushOutboxStore
import org.mockito.Mockito.any
import org.mockito.Mockito.anyList
import org.mockito.Mockito.doAnswer
import java.time.Instant

/**
 * 목 아웃박스 저장소의 묶음 기록을 같은 목의 단건 기록으로 넘긴다(KNK-2077). 예약 회차는 [PushOutboxStore.insertAll]로
 * 기록하지만 기존 테스트는 단건 [PushOutboxStore.insert] 스텁으로 요청을 모으고 실패를 주입하므로, 그 스텁을 그대로 쓴다.
 * 목은 테스트마다 reset되므로 `reset` 뒤에 부른다.
 */
fun PushOutboxStore.forwardInsertAllToInsert() {
    val store = this
    doAnswer { call ->
        val now = call.getArgument<Instant>(1)
        call.getArgument<List<PushMessage>>(0).sumOf { store.insert(it, now) }
    }.`when`(store).insertAll(anyList(), any(Instant::class.java) ?: Instant.EPOCH)
}
