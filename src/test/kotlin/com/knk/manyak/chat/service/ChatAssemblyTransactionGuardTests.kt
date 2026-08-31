package com.knk.manyak.chat.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

/**
 * AI 턴 요청 조립 구간 트랜잭션 설정의 회귀 가드(KNK-1064 Codex P2).
 *
 * 조립은 게이트 판정에 쓰는 스토리와 그 자식 데이터(시작 설정·설정·사건·엔딩)를 여러 쿼리로 나눠 읽는다.
 * READ_COMMITTED에서는 그 사이에 제작자의 "비공개 전환 + 프롤로그 수정" 커밋이 끼면 게이트는 옛 공개 상태로
 * 열린 채 **새 프롤로그가 AI 요청에 실려** 비공개 개작이 생성 결과로 샌다. 그래서 REPEATABLE_READ 읽기 전용이다.
 *
 * 경합 자체를 통합 테스트로 재현하려면 쿼리와 쿼리 사이에 다른 트랜잭션의 커밋을 정확히 끼워야 해서
 * 훅을 심거나 타이밍에 기대야 한다(KNK-1059에서 같은 결론). 그래서 여기서는 **설정이 걸려 있다는 사실**을 고정한다.
 * 턴 경로 자체에 `@Transactional`을 붙이지 않는 이유(AI 호출·스트리밍이 커넥션을 수십 초 붙든다)는
 * [ChatService]의 `assemblyTransactionTemplate` KDoc에 있다.
 */
class ChatAssemblyTransactionGuardTests {

    /** 설정만 읽으므로 실제로 트랜잭션을 열 일이 없다. */
    private val noopTransactionManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            error("가드 테스트는 트랜잭션을 열지 않는다")

        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }

    @Test
    fun `조립 구간 트랜잭션은 REPEATABLE_READ 읽기 전용이다`() {
        val template = ChatService.assemblyTransactionTemplate(noopTransactionManager)

        assertThat(template.isolationLevel)
            .withFailMessage(
                "조립 구간 격리 수준이 REPEATABLE_READ가 아닙니다(actual=%d). " +
                    "게이트와 자식 데이터를 한 스냅샷에서 읽지 못하면 비공개 개작이 AI 요청으로 샙니다.",
                template.isolationLevel,
            )
            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ)

        assertThat(template.isReadOnly)
            .withFailMessage("조립 구간은 읽기 전용이어야 합니다(PostgreSQL에서 락을 잡지 않게).")
            .isTrue()

        // 기본 전파(REQUIRED)여야 조립이 끝나는 즉시 커밋된다 — AI 호출·스트리밍으로 넘어가면 안 된다.
        assertThat(template.propagationBehavior)
            .withFailMessage("조립 구간은 기본 전파(REQUIRED)여야 합니다.")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
    }
}
