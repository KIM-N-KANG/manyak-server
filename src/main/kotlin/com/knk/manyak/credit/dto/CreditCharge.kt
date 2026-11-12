package com.knk.manyak.credit.dto

/** 차감으로 생긴 원장 행(음수 [amount]). 호출 도메인이 환불 키(`refund:charge:{id}`)와 저장 연결에 쓴다. */
data class CreditCharge(val id: Long, val userId: Long, val amount: Long, val reason: CreditReason, val refType: String?)
