package com.knk.manyak.admin.infra

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
import java.util.concurrent.atomic.AtomicReference

data class InfraTaskCounts(val running: Int, val desired: Int, val pending: Int)
data class InfraAwsValues(val series: Map<String, List<InfraMetricPoint>>, val tasks: Map<String, InfraTaskCounts>)
@Component
class InfraAwsGateway(private val cloudwatch: CloudWatchInfraClient, private val ecs: EcsClient,
    private val catalog: InfraMetricCatalog, private val properties: InfraMetricsProperties) {
    fun fetch(range: InfraRange, deadline: Long, stage: AtomicReference<String>): InfraAwsValues {
        stage.set("cloudwatch")
        val series = cloudwatch.fetch(catalog.queries(range.periodSeconds), range.since, range.until, deadline)
        val pairs = properties.servicePairs()
        val tasks = if (pairs.isEmpty()) emptyMap() else infraCall("ecs") {
            stage.set("ecs")
            val response = ecs.describeServices(DescribeServicesRequest.builder().cluster(properties.cluster).services(pairs.map { it.second })
                .overrideConfiguration { it.apiCallTimeout(remaining(deadline, "ecs")) }.build())
            remaining(deadline, "ecs")
            check(response.failures().isEmpty())
            val services = response.services().associateBy { it.serviceName() }
            pairs.associate { (_, name) ->
                val service = requireNotNull(services[name])
                check(service.runningCount() >= 0 && service.desiredCount() >= 0 && service.pendingCount() >= 0)
                name to InfraTaskCounts(service.runningCount(), service.desiredCount(), service.pendingCount())
            }
        }
        return InfraAwsValues(series, tasks)
    }
}
