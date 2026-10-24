package com.knk.manyak.push.delivery

import com.knk.manyak.push.dto.*
import com.knk.manyak.push.repository.PushDeliveryRepository
import org.assertj.core.api.Assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.*
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushOpensPostgresTests {
    private val pg=GenericContainer("postgres:16-alpine").withEnv("POSTGRES_PASSWORD","push-opens-test")
        .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2))
    private lateinit var jdbc:JdbcTemplate
    private lateinit var repository:PushDeliveryRepository
    private val from=LocalDate.parse("2026-10-23")
    private val to=from.plusDays(1)
    private var uid=0L
    private var other=0L
    private val ids=List(4) { UUID.randomUUID() }
    @BeforeAll fun start() {
        pg.start()
        val ds=DriverManagerDataSource("jdbc:postgresql://${pg.host}:${pg.getMappedPort(5432)}/postgres","postgres","push-opens-test")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("111").load().migrate()
        jdbc=JdbcTemplate(ds);repository=PushDeliveryRepository(jdbc)
        uid=jdbc.queryForObject("INSERT INTO users(public_id,nickname) VALUES (?,'수신 회원') RETURNING id",Long::class.java,UUID.randomUUID())!!
        other=jdbc.queryForObject("INSERT INTO users(public_id,nickname,status) VALUES (?,'탈퇴 회원','DELETED') RETURNING id",Long::class.java,UUID.randomUUID())!!
        for((index,id) in ids.withIndex()) jdbc.update("""INSERT INTO push_campaigns(public_id,title,body,scheduled_at,started_at,status)
            VALUES (?,'캠페인','본문','2026-10-20T00:00:00Z',?,?)""",id,
            when(index) {0->OffsetDateTime.parse("2026-10-23T00:00:00Z");1->OffsetDateTime.parse("2026-10-24T00:00:00Z");2->OffsetDateTime.parse("2026-10-22T14:59:59Z");else->null},if(index==3) "CANCELED" else "SENT")
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
        jdbc.update("UPDATE push_campaigns SET deep_link_included=TRUE WHERE public_id=?",ids[0])
        jdbc.update("UPDATE push_campaigns SET deep_link_included=FALSE WHERE public_id=?",ids[1])
        val campaign=repository.findCampaignId(ids[0])!!
        fun report(type:PushDeliveryType,user:Long?,at:String,cid:Long?=null,delivered:Int=1) {
            repository.insert(PushDeliveryReportRequest(messageId=UUID.randomUUID().toString(),type=type,kind=PushKind.SERVICE,
                recipientId=UUID.randomUUID(),outcome=if(delivered>0)PushDeliveryOutcome.DELIVERED else PushDeliveryOutcome.UNDELIVERED,
                reason="fixture",deliveredDevices=delivered,failedDevices=0,completedAt=Instant.parse(at)),cid,user)
        }
        report(PushDeliveryType.NOTICE,uid,"2026-10-23T00:00:00Z",campaign)
        report(PushDeliveryType.NOTICE,other,"2026-10-23T00:00:00Z",campaign,2)
        report(PushDeliveryType.NOTICE,uid,"2026-10-23T00:00:00Z",campaign,0)
        repeat(3) { report(PushDeliveryType.STORY_COMPLETED,uid,"2026-10-23T00:00:00Z") }
        report(PushDeliveryType.STORY_COMPLETED,null,"2026-10-23T00:00:00Z")
        for(at in listOf("2026-10-23T00:00:00Z","2026-10-24T00:00:00Z")) {
            report(PushDeliveryType.ATTENDANCE_REMINDER,uid,at);report(PushDeliveryType.ATTENDANCE_REMINDER,other,at)
        }
        report(PushDeliveryType.ATTENDANCE_REMINDER,uid,"2026-10-24T00:00:00Z",delivered=0)
        report(PushDeliveryType.STORY_COMPLETED,other,"2026-10-22T14:59:59Z")
        report(PushDeliveryType.STORY_COMPLETED,other,"2026-10-24T15:00:00Z")
    }
    @AfterAll fun stop() { pg.stop() }
    @Test fun `V112는 기존 발송 기록을 추측하지 않고 nullable 기본값 없음으로 추가한다`() {
        assertThat(jdbc.queryForObject("SELECT deep_link_included FROM push_campaigns WHERE public_id=?",Boolean::class.java,ids[2])).isNull()
        val col=jdbc.queryForMap("SELECT is_nullable,column_default FROM information_schema.columns WHERE table_name='push_campaigns' AND column_name='deep_link_included'")
        assertThat(col["is_nullable"]).isEqualTo("YES");assertThat(col["column_default"]).isNull()
    }
    @Test fun `마지막 선점이 기간 안인 캠페인만 반환하고 상세 도달과 같은 분모를 쓴다`() {
        val rows=repository.campaignReach(from,to)
        assertThat(rows.map { it.campaignId }).containsExactly(ids[1],ids[0])
        assertThat(rows.map { it.deepLinkIncluded }).containsExactly(false,true)
        assertThat(rows.last().deliveredMembers).isEqualTo(repository.campaignStats(repository.findCampaignId(ids[0])!!).deliveredMembers)
    }
    @Test fun `활동 도달은 KST 날짜 종류 회원별 중복을 빼고 null 회원과 미도달을 제외한다`() {
        val rows=repository.activityReach(from,to,listOf(PushDeliveryType.STORY_COMPLETED,PushDeliveryType.ATTENDANCE_REMINDER))
        assertThat(rows.filter { it.type==PushDeliveryType.STORY_COMPLETED }.single().deliveredMembers).isEqualTo(1)
        assertThat(rows.filter { it.type==PushDeliveryType.ATTENDANCE_REMINDER }.map { it.deliveredMembers }).containsExactly(2,2)
        assertThat(rows.map { it.date }).containsOnly(from,to)
    }
}
