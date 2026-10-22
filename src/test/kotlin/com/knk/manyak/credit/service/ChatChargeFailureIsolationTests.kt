package com.knk.manyak.credit.service

import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.*

class ChatChargeFailureIsolationTests {
    @Test fun `차감 행 하나의 실패가 다음 차감 환불을 막지 않고 실패 이벤트를 남긴다`() {
        val transactions = mock(CreditTransactionRepository::class.java)
        val rows = mock(ChatChargeReconciler::class.java)
        val logger = mock(StructuredLogger::class.java)
        val now = Instant.parse("2026-10-22T00:00:00Z")
        val cutoff = now.minusSeconds(900)
        `when`(transactions.findStuckChargeGroups(listOf(CreditReason.STORY_CREATION, CreditReason.CHAT_TURN), cutoff)).thenReturn(emptyList())
        `when`(transactions.findChatTurnCharges(cutoff, 0, org.springframework.data.domain.PageRequest.of(0, 500))).thenReturn(listOf(
            CreditTransaction(id = 1, userId = 10, amount = -50, reason = CreditReason.CHAT_TURN),
            CreditTransaction(id = 2, userId = 10, amount = -20, reason = CreditReason.CHAT_TURN),
        ))
        `when`(transactions.findChatTurnCharges(cutoff, 2, org.springframework.data.domain.PageRequest.of(0, 500))).thenReturn(emptyList())
        `when`(rows.reconcile(1)).thenThrow(IllegalStateException("charge failure"))
        `when`(rows.reconcile(2)).thenReturn(true)
        val service = CreditReconciliationService(transactions, mock(CreditWalletService::class.java), mock(StoryCreationSessionRepository::class.java), logger, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC), rows)
        val result = service.reconcile()
        assertThat(result.chargesScanned).isEqualTo(2)
        assertThat(result.refundsEmitted).isEqualTo(1)
        assertThat(mockingDetails(logger).invocations.any { it.arguments.firstOrNull() == "credit_reconciliation_charge_failed" }).isTrue()
    }
    @Test fun `선두 오백 건이 계속 실패해도 다음 페이지 정상 한 건을 같은 실행에서 환불한다`() {
        val transactions = mock(CreditTransactionRepository::class.java)
        val rows = mock(ChatChargeReconciler::class.java)
        val now = Instant.parse("2026-10-22T00:00:00Z")
        val cutoff = now.minusSeconds(900)
        val page = org.springframework.data.domain.PageRequest.of(0, 500)
        `when`(transactions.findStuckChargeGroups(listOf(CreditReason.STORY_CREATION, CreditReason.CHAT_TURN), cutoff)).thenReturn(emptyList())
        `when`(transactions.findChatTurnCharges(cutoff, 0, page)).thenReturn((1L..500L).map { CreditTransaction(id = it, userId = 10, amount = -50, reason = CreditReason.CHAT_TURN, refType = "CHAT_TURN") })
        `when`(transactions.findChatTurnCharges(cutoff, 500, page)).thenReturn(listOf(CreditTransaction(id = 501, userId = 10, amount = -20, reason = CreditReason.CHAT_TURN, refType = "CHAT_TURN")))
        `when`(transactions.findChatTurnCharges(cutoff, 501, page)).thenReturn(emptyList())
        for (id in 1L..500L) `when`(rows.reconcile(id)).thenThrow(IllegalStateException("persistent failure"))
        `when`(rows.reconcile(501)).thenReturn(true)
        val service = CreditReconciliationService(transactions, mock(CreditWalletService::class.java), mock(StoryCreationSessionRepository::class.java), mock(StructuredLogger::class.java), Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC), rows)
        val result = service.reconcile()
        assertThat(result.chargesScanned).isEqualTo(501)
        assertThat(result.refundsEmitted).isEqualTo(1)
        verify(rows).reconcile(501)
    }

}
