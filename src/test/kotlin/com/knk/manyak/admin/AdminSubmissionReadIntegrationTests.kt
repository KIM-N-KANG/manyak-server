package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.submission.*
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@org.springframework.context.annotation.Import(com.knk.manyak.auth.controller.FakeSocialLoginConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSubmissionReadIntegrationTests {
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var submissions: StorySubmissionRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var forms: SubmissionFormAssembler
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    lateinit var imageStorage: com.knk.manyak.image.service.UploadedImageStorage
    @Autowired lateinit var mapper: ObjectMapper
    private val now = Instant.parse("2026-10-23T15:00:00Z")
    private lateinit var admin: User
    private lateinit var owner: User
    private lateinit var token: String
    private val payload = """{"title":"등록 제목 %_","oneLineIntro":"한 줄","description":"소개","protagonistName":"서린","genres":["판타지"],"visibility":"PRIVATE","storySettings":{"worldSetting":"private-setting","characterSetting":"private-setting","userRoleSetting":"private-setting","ruleSetting":"private-setting"},"startSettings":[],"characters":[{"name":"도윤","description":"private-description","images":[]}]}"""
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        org.mockito.Mockito.`when`(imageStorage.serveUrlOf(org.mockito.ArgumentMatchers.anyString())).thenAnswer { "https://private-image.test/" + it.getArgument<String>(0) }
        jdbc.execute("CREATE ALIAS IF NOT EXISTS jsonb_extract_path_text FOR 'com.knk.manyak.admin.AdminSubmissionJsonFunction.text'")
        admin=users.save(User(nickname="관리자")); owner=users.save(User(nickname="소유자"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)",admin.id)
        token=jwt.issueAdminToken(admin.publicId)
    }
    private fun row(status: SubmissionStatus=SubmissionStatus.PENDING, held: Boolean=false, at: Instant=now, story: Story?=null, issues: List<ModerationIssue> = emptyList()) = submissions.save(StorySubmission(
        userId=owner.id, kind=if(story==null) SubmissionKind.CREATE else SubmissionKind.UPDATE, storyId=(story ?: if(status==SubmissionStatus.APPROVED) stories.save(Story(userId=owner.id,title="승인 뒤 제목",genre="판타지")) else null)?.id, payload=if(story==null) payload else "{}", inputForm=payload,
        status=status,heldAt=if(held) now else null,holdReason=if(held) "MODEL_CALL_FAILED" else null,errorCode=if(status==SubmissionStatus.FAILED) "IMAGE_UNREADABLE" else null,
        attempt=3,retryCount=2,createdAt=at,decidedAt=if(status!=SubmissionStatus.PENDING) at.plusSeconds(10) else null,issues=issues))
    private fun get(suffix: String="", auth: String?=token): RestTestClient.ResponseSpec {
        val r=client.get().uri(java.net.URI.create("/api/v1/admin/moderation/submissions$suffix"))
        auth?.let { r.header("Authorization","Bearer $it") }; return r.exchange()
    }
    private fun body(suffix: String="")=mapper.readTree(get(suffix).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun ids(suffix: String="")=body(suffix)["items"].toList().map { it["submissionId"].asText() }
    @Test fun `관리자 인증은 목록과 상세에 같은 게이트를 적용한다`() {
        val r=row()
        for(s in listOf("","/${r.publicId}")) {
            get(s,null).expectStatus().isUnauthorized
            get(s,jwt.issueAccessToken(owner.publicId)).expectStatus().isUnauthorized
            get(s,jwt.issueAdminToken(owner.publicId)).expectStatus().isForbidden
        }
    }
    @Test fun `전체 다섯 상태를 접수 시각 내림차순으로 조회하고 상태별로 거른다`() {
        val rows=listOf(row(),row(held=true),row(SubmissionStatus.APPROVED),row(SubmissionStatus.REJECTED),row(SubmissionStatus.FAILED))
        assertThat(ids()).containsExactlyElementsOf(rows.map { it.publicId.toString() }.sorted())
        for((r,status) in rows.zip(listOf("PENDING","HELD","APPROVED","REJECTED","FAILED"))) {
            val b=body("?status=$status"); assertThat(b["items"][0]["status"].asText()).isEqualTo(status)
            assertThat(b["totalElements"].asLong()).isEqualTo(1); assertThat(ids("?status=$status")).containsExactly(r.publicId.toString())
        }
    }
    @Test fun `스토리가 없는 반려 등록은 요청 제목과 저장된 순서의 위반 요약을 반환한다`() {
        val issues=listOf(ModerationIssue("characters[4].description","TEXT","DRUGS","첫 이유"),ModerationIssue("storySettings.worldSetting","TEXT","EXTREME_GORE","둘"),ModerationIssue("title","TEXT","DRUGS","셋"))
        row(SubmissionStatus.REJECTED,issues=issues)
        val b=body()["items"][0]
        assertThat(b["story"]["storyId"].isNull).isTrue(); assertThat(b["story"]["title"].asText()).isEqualTo("등록 제목 %_")
        assertThat(b["issueCount"].asInt()).isEqualTo(3); assertThat(b["issueRules"].toList().map { it.asText() }).containsExactly("DRUGS","EXTREME_GORE")
        assertThat(b["firstIssueReason"].asText()).isEqualTo("첫 이유")
        assertThat(b["owner"].properties().map { it.key }).containsExactlyInAnyOrder("memberId","nickname","status","isAdmin","createdAt")
        assertThat(b["owner"]["memberId"].asText()).isEqualTo(owner.publicId.toString())
        assertThat(b["holdReason"].isNull).isTrue(); assertThat(b["errorCode"].isNull).isTrue()
    }
    @Test fun `스토리 제목은 현재 행에서 읽고 회원 스토리와 리터럴 제목 검색을 적용한다`() {
        val story=stories.save(Story(userId=owner.id,title="현재 제목",genre="판타지")); val r=row(story=story); val fresh=row()
        assertThat(ids("?storyId=${story.publicId}&memberId=${owner.publicId}")).containsExactly(r.publicId.toString())
        assertThat(ids("?q=%ED%98%84%EC%9E%AC")).containsExactly(r.publicId.toString())
        assertThat(ids("?q=%25_")).containsExactly(fresh.publicId.toString())
        assertThat(ids("?q=${r.publicId}")).containsExactly(r.publicId.toString())
        assertThat(ids("?memberId=${UUID.randomUUID()}")).isEmpty(); assertThat(ids("?storyId=${UUID.randomUUID()}")).isEmpty()
    }
    @Test fun `기간은 KST 자정 포함과 다음 자정 제외이며 페이지 경계를 지킨다`() {
        row(at=now.minusNanos(1000)); val first=row(at=now); val last=row(at=now.plusSeconds(86399)); row(at=now.plusSeconds(86400))
        assertThat(ids("?from=2026-10-24&to=2026-10-24")).containsExactly(last.publicId.toString(),first.publicId.toString())
        val b=body("?size=1&page=2&from=2026-10-24&to=2026-10-24"); assertThat(b["items"][0]["submissionId"].asText()).isEqualTo(first.publicId.toString()); assertThat(b["totalPages"].asInt()).isEqualTo(2)
        assertThat(ids("?page=10")).isEmpty()
    }
    @Test fun `판정과 보류 정렬은 null을 뒤에 두고 동률은 공개 UUID 오름차순이다`() {
        val a=row(SubmissionStatus.REJECTED,at=now.minusSeconds(2)); val b=row(SubmissionStatus.FAILED,at=now.minusSeconds(1)); val c=row(); val d=row(held=true)
        assertThat(ids("?sort=decidedAt&order=asc")).containsExactlyElementsOf(listOf(a.publicId.toString(),b.publicId.toString()) + listOf(c.publicId.toString(),d.publicId.toString()).sorted())
        assertThat(ids("?sort=heldAt&order=desc").first()).isEqualTo(d.publicId.toString())
        assertThat(body("?status=HELD")["items"][0]["holdReason"].asText()).isEqualTo("MODEL_CALL_FAILED")
        assertThat(body("?status=FAILED")["items"][0]["errorCode"].asText()).isEqualTo("IMAGE_UNREADABLE")
    }
    @ParameterizedTest @ValueSource(strings=["status=DRAFT","status=","sort=updatedAt","order=ASC","memberId=abc","storyId=abc","page=0","size=101","limit=20","cursor=x","from=2026-02-30","from=2026-10-25&to=2026-10-24","q=","q=%20%20"])
    fun `쿼리 오류는 400이다`(query: String) { get("?$query").expectStatus().isBadRequest }
    @Test fun `상세는 복원된 허용 필드와 위치만 보이고 원문과 내부 값은 숨긴다`() {
        val r=row(SubmissionStatus.REJECTED,issues=listOf(ModerationIssue("storySettings.worldSetting","TEXT","DRUGS","이유")))
        val b=body("/${r.publicId}")
        assertThat(b["fields"]["title"].asText()).isEqualTo("등록 제목 %_")
        assertThat(b["fields"]["characterNames"].toList().map { it.asText() }).containsExactly("도윤")
        assertThat(b["fields"]["genres"].toList().map { it.asText() }).containsExactly("판타지")
        assertThat(b["fields"]["oneLineIntro"].asText()).isEqualTo("한 줄"); assertThat(b["fields"]["protagonistName"].asText()).isEqualTo("서린")
        assertThat(b["issues"][0]["path"].asText()).isEqualTo("storySettings.worldSetting")
        assertThat(b["retryCount"].asInt()).isEqualTo(2); assertThat(b.has("updatedAt")).isTrue()
        for(node in listOf(body(),b)) assertThat(node.toString()).doesNotContain("private-setting","private-description","inputForm","payload","imageCopies","dispatchedAt","nextAttemptAt","objectKey","imageUrl","thumbnailUrl")
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs",Long::class.java)).isZero()
    }
    @Test fun `승인 상세는 접수 시점 폼이고 저장 요약과 재대응된 상세는 구분한다`() {
        val r=row(SubmissionStatus.APPROVED,issues=listOf(ModerationIssue("characters[9].description","TEXT","DRUGS","삭제됨")))
        val b=body("/${r.publicId}"); assertThat(b["issueCount"].asInt()).isEqualTo(1); assertThat(b["issues"].size()).isZero()
        assertThat(b["fields"]["title"].asText()).isEqualTo("등록 제목 %_")
    }
    @Test fun `삭제된 인물의 위반은 현재 폼 상세에서 빠져도 저장 요약은 유지한다`() {
        val story=stories.save(Story(userId=owner.id,title="현재 복원 제목",genre="판타지"))
        val r=row(SubmissionStatus.REJECTED,story=story,issues=listOf(ModerationIssue("characters[0].description","TEXT","DRUGS","삭제된 인물")))
        r.imageErrors=listOf(SubmissionImageError("characters[0].images[0].imageUrl","IMAGE_INVALID"))
        val original=mapper.readTree(r.inputForm) as tools.jackson.databind.node.ObjectNode
        (original["characters"][0] as tools.jackson.databind.node.ObjectNode).set("images",mapper.readTree("""[{"id":"deleted-image","imageUrl":"https://private-image.test/deleted"}]"""))
        r.inputForm=mapper.writeValueAsString(original);submissions.save(r)
        val b=body("/${r.publicId}")
        assertThat(b["imageResults"].size()).isZero()
        assertThat(b["fields"]["title"].asText()).isEqualTo("현재 복원 제목")
        assertThat(b["issueCount"].asInt()).isEqualTo(1); assertThat(b["issues"].size()).isZero()
        assertThat(b["fields"]["characterNames"].size()).isZero()
    }
    @Test fun `UUID 모양 제목도 식별자 검색과 함께 일치하며 검색을 정규화한다`() {
        val a=row(); val b=row()
        val form=mapper.readTree(payload) as tools.jackson.databind.node.ObjectNode
        form.put("title",a.publicId.toString()); b.payload=mapper.writeValueAsString(form);submissions.save(b)
        assertThat(ids("?q=${a.publicId}")).containsExactlyElementsOf(listOf(a.publicId.toString(),b.publicId.toString()).sorted())
        assertThat(ids("?q=${java.net.URLEncoder.encode(" 등록  제목 ",java.nio.charset.StandardCharsets.UTF_8)}")).containsExactly(a.publicId.toString())
    }
    @Test fun `OpenAPI도 관리자 상세 요약을 평탄하게 문서화한다`() {
        val docs=mapper.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        val schema=docs["components"]["schemas"]["AdminSubmissionDetail"]["properties"]
        assertThat(schema.has("submissionId")).isTrue(); assertThat(schema.has("fields")).isTrue();assertThat(schema.has("summary")).isFalse()
        assertThat(docs["paths"].has("/api/v1/admin/moderation/submissions/{submissionId}")).isTrue()
        val parameters=docs["paths"]["/api/v1/admin/moderation/submissions"]["get"]["parameters"].toList().map { it["name"].asText() }
        assertThat(parameters).contains("status","memberId","storyId","q","from","to","sort","order","page","size")
    }
    @Test fun `승인 이미지 결과는 표지와 인물 이미지 경로 이름 상태만 보이고 URL을 숨긴다`() {
        val r=row(SubmissionStatus.APPROVED)
        val form=mapper.readTree(payload) as tools.jackson.databind.node.ObjectNode
        form.put("thumbnailUrl","https://private-image.test/cover");form.put("thumbnailModerationStatus","APPROVED")
        val character=form["characters"][0] as tools.jackson.databind.node.ObjectNode
        character.set("images",mapper.readTree("""[{"imageName":"도윤_기본","imageUrl":"https://private-image.test/character","objectKey":"private-object","moderationStatus":"APPROVED"}]"""))
        r.inputForm=mapper.writeValueAsString(form);submissions.save(r)
        val b=body("/${r.publicId}")
        assertThat(b["imageResults"].toList().map { it["path"].asText() }).containsExactly("thumbnailUrl","characters[0].images[0].imageUrl")
        assertThat(b["imageResults"].toList().map { it["label"].asText() }).containsExactly("표지","도윤_기본")
        assertThat(b["imageResults"].toList().map { it["moderationStatus"].asText() }).containsOnly("APPROVED")
        assertThat(b["imageResults"].toList().all { it["errorCode"].isNull }).isTrue()
        assertThat(b.toString()).doesNotContain("https://private-image.test","private-object","thumbnailModerationStatus")
    }
    @Test fun `동률과 null 동률은 정렬 방향과 무관하게 UUID 순서로 페이지를 나눈다`() {
        val expected=List(5) { row().publicId.toString() }.sorted()
        for(sort in listOf("createdAt","decidedAt","heldAt")) for(order in listOf("asc","desc")) {
            val actual=(1..3).flatMap { page -> ids("?size=2&page=$page&sort=$sort&order=$order") }
            assertThat(actual).containsExactlyElementsOf(expected);assertThat(actual.distinct()).hasSize(5)
        }
    }
    @Test fun `이미지 상태는 폼 값 대신 재대응한 위반 오류와 제출 판정으로 정한다`() {
        val imagePath="characters[0].images[0].imageUrl"
        for((status,held) in listOf(SubmissionStatus.APPROVED to false,SubmissionStatus.REJECTED to false,SubmissionStatus.FAILED to false,SubmissionStatus.PENDING to false,SubmissionStatus.PENDING to true)) {
            val r=row(status,held=held)
            val rich=mapper.readTree(payload) as tools.jackson.databind.node.ObjectNode
            rich.put("thumbnailObjectKey","cover-key")
            (rich["characters"][0] as tools.jackson.databind.node.ObjectNode).set("images",mapper.readTree("""[{"objectKey":"character-key","imageName":"도윤_기본"}]"""))
            r.payload=mapper.writeValueAsString(rich)
            val original=forms.create(mapper.readValue(r.payload,com.knk.manyak.story.dto.CreateGeneralStoryRequest::class.java),owner.id,false)
            original.put("thumbnailModerationStatus","REJECTED")
            (original["characters"][0]["images"][0] as tools.jackson.databind.node.ObjectNode).put("moderationStatus","REJECTED")
            r.inputForm=mapper.writeValueAsString(original)
            if(status==SubmissionStatus.REJECTED) {
                r.issues=listOf(ModerationIssue("thumbnailUrl","IMAGE","DRUGS","표지 이유"))
                r.imageErrors=listOf(SubmissionImageError("thumbnailUrl","IMAGE_INVALID"))
            }
            if(status==SubmissionStatus.FAILED) r.imageErrors=listOf(SubmissionImageError(imagePath,"IMAGE_INVALID"))
            submissions.save(r)
            val images=body("/${r.publicId}")["imageResults"].toList()
            assertThat(images.map { it["path"].asText() }).containsExactly("thumbnailUrl",imagePath)
            val expected=when(status) { SubmissionStatus.APPROVED -> listOf("APPROVED","APPROVED");SubmissionStatus.REJECTED -> listOf("REJECTED","APPROVED");SubmissionStatus.FAILED -> listOf("PENDING","ERROR");else -> listOf("PENDING","PENDING") }
            assertThat(images.map { it["moderationStatus"].asText() }).containsExactlyElementsOf(expected)
            assertThat(images[0]["errorCode"].isNull).isTrue()
            if(status==SubmissionStatus.FAILED) assertThat(images[1]["errorCode"].asText()).isEqualTo("IMAGE_INVALID") else assertThat(images[1]["errorCode"].isNull).isTrue()
            assertThat(images.map { it["label"].asText() }).containsExactly("표지","도윤_기본")
            assertThat(body("/${r.publicId}").toString()).doesNotContain("private-image.test","cover-key","character-key")
        }
    }
    @Test fun `없는 제출본과 잘못된 상세 식별자는 404다`() { get("/abc").expectStatus().isNotFound; get("/${UUID.randomUUID()}").expectStatus().isNotFound }
    @Test fun `승인 반려 변경 경로가 없다`() {
        val r=row(); for(action in listOf("approve","reject")) client.post().uri("/api/v1/admin/moderation/submissions/${r.publicId}/$action").header("Authorization","Bearer $token").exchange().expectStatus().isNotFound
    }
}
object AdminSubmissionJsonFunction {
    @JvmStatic fun text(value: String, key: String): String? {
        val mapper=ObjectMapper(); var node=mapper.readTree(value); if(node.isString) node=mapper.readTree(node.asText())
        return node[key]?.takeUnless { it.isNull }?.asText()
    }
}
