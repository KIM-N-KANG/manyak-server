package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 채팅 읽기 경로의 트랜잭션 격리 수준 회귀 가드(KNK-1059).
 *
 * 이 경로들은 **게이트 판정(스토리)과 표시 값(시작 설정·엔딩)을 여러 쿼리로 나눠 읽는다.**
 * KNK-1065가 스냅샷을 스토리 행으로 옮겨 스냅샷 분기의 경합은 사라졌지만, **읽기가 허용된 분기는 여전히
 * 자식 행을 따로 읽으므로** 이 격리는 그대로 필요하다.
 * READ_COMMITTED에서는 쿼리 사이에 소유자의 "비공개 전환 + 제목·프롤로그 수정" 커밋이 끼어들 수 있고,
 * 그러면 게이트는 옛 공개 상태로 열린 채 자식 데이터만 새 값이 나와 막으려던 유출이 그 틈으로 다시 샌다.
 * 한 스냅샷에서 읽도록 REPEATABLE_READ로 묶었으며, 이 가드가 그 설정이 조용히 사라지는 것을 막는다.
 *
 * 경합 자체를 통합 테스트로 재현하려면 두 트랜잭션의 커밋 시점을 쿼리 사이에 정확히 끼워 넣어야 해서
 * 결정적으로 만들기 어렵다. 그래서 여기서는 **설정이 걸려 있다는 사실**을 고정한다.
 */
class ChatReadIsolationGuardTests {

    private val readPaths = listOf(
        "getChatsByIds",
        "getMyChats",
        "getChatDetail",
        "getChatShare",
    )

    @Test
    fun `채팅 읽기 경로는 REPEATABLE_READ 읽기 전용 트랜잭션이다`() {
        for (name in readPaths) {
            val method = ChatService::class.java.methods.first { it.name == name }
            val tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional::class.java)
                ?: error("$name 에 @Transactional 이 없습니다.")

            assertThat(tx.isolation)
                .withFailMessage(
                    "%s 의 격리 수준이 REPEATABLE_READ가 아닙니다(actual=%s). " +
                        "게이트와 표시 값을 한 스냅샷에서 읽지 못하면 비공개 전환 경합으로 유출이 재발합니다.",
                    name,
                    tx.isolation,
                )
                .isEqualTo(Isolation.REPEATABLE_READ)
            assertThat(tx.readOnly)
                .withFailMessage("%s 는 읽기 전용이어야 합니다.", name)
                .isTrue()
        }
    }
}
