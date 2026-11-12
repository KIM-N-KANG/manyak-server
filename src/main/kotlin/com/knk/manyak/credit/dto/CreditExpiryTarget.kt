package com.knk.manyak.credit.dto

import java.time.Instant

interface CreditExpiryTarget {
    val userId: Long
    val expiresAt: Instant
}
