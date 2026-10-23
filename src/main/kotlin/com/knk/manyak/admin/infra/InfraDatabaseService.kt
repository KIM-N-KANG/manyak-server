package com.knk.manyak.admin.infra

import org.springframework.stereotype.Service
import java.time.Clock

@Service
class InfraDatabaseService(private val repository: InfraDatabaseRepository, private val properties: InfraMetricsProperties,
    private val clock: Clock = Clock.systemUTC()) {
    private val cache = mutableMapOf<Int, InfraDatabaseResponse>()
    @Synchronized fun tables(limit: Int): InfraDatabaseResponse {
        cache[limit]?.takeIf { clock.instant().isBefore(it.asOf.plusSeconds(properties.databaseCacheSeconds)) }?.let { return it }
        val raw = repository.snapshot(limit)
        val result = InfraDatabaseResponse(clock.instant(), raw.databaseBytes, raw.tableCount,
            raw.tables.sortedWith(compareByDescending<InfraTableSize> { it.totalBytes }.thenBy { it.schema }.thenBy { it.name }).take(limit))
        cache[limit] = result
        return result
    }
}
