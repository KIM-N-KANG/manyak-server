package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.AddCharacterImageRequest
import com.knk.manyak.story.dto.CharacterImageResponse
import com.knk.manyak.story.dto.ImagePresignRequest
import com.knk.manyak.story.dto.ImagePresignResponse
import com.knk.manyak.story.service.StoryImageService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 스토리 이미지 업로드(KNK-1126, 스펙 §4-3-8 스토리 이미지 업로드).
 *
 * 표지 **교체**는 스토리 수정(`PATCH /stories/{storyId}`)의 `thumbnailObjectKey`가 담당하고 여기에는 없다 —
 * 편집 폼 저장 한 번으로 끝나야 하기 때문이다. 여기는 발급·삭제와 인물 이미지다.
 *
 * 네 경로 모두 **인증 필수**다. SecurityConfig에 개별 permitAll이 없어 `anyRequest().authenticated()`가
 * 적용되므로(스토리 상세·수정과 달리 익명 허용 목록에 넣지 않았다) 미인증은 401이다.
 */
@Tag(name = "Stories", description = "스토리 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/stories/{storyId}")
class StoryImageController(
    private val storyImageService: StoryImageService,
) {

    @Operation(
        summary = "이미지 업로드용 presigned URL 발급",
        description = "클라이언트가 S3에 직접 올릴 수 있는 서명 URL을 발급합니다(KNK-1126). 파일이 서버를 지나지 " +
            "않습니다. 서명에 `Content-Type`·`Content-Length`가 고정되므로 클라이언트는 요청한 값 그대로 PUT해야 " +
            "합니다. 객체 키는 서버가 정하며 만료는 10분입니다. PUT을 마친 뒤 그 `objectKey`를 표지 교체" +
            "(`PATCH /stories/{storyId}`의 `thumbnailObjectKey`)나 인물 이미지 연결 요청에 넣습니다. " +
            "회원 소유 스토리만이며 게스트 소유(이관 전) 스토리는 400입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "발급 성공",
                content = [Content(schema = Schema(implementation = ImagePresignResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "지원하지 않는 형식·크기 초과, 또는 게스트 소유 스토리",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "타인 스토리 또는 정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(
                responseCode = "503",
                description = "이미지 저장소가 설정되지 않음(로컬 기본값)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/images/presign")
    fun presign(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: ImagePresignRequest,
    ): ImagePresignResponse = storyImageService.presign(storyId, requireUser(userId), request)

    @Operation(
        summary = "표지 삭제",
        description = "업로드·생성 표지 URL을 지워 프리셋 표지로 되돌립니다(KNK-1126). 프리셋 키는 건드리지 " +
            "않으므로 표지가 사라지는 것이 아니라 자동 연결된 프리셋으로 내려갑니다. 표지가 없어도 204입니다(멱등). " +
            "S3 객체는 지우지 않습니다 — 지난 채팅 카드가 그 URL을 가리킬 수 있습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 완료(표지가 없던 경우 포함)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "400", description = "게스트 소유 스토리", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "타인 스토리 또는 정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/thumbnail")
    fun deleteThumbnail(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ) = storyImageService.deleteThumbnail(storyId, requireUser(userId))

    @Operation(
        summary = "인물 이미지 연결",
        description = "업로드한 이미지를 인물에 연결합니다(KNK-1126). 이름은 `{인물이름}_{접미}` 형식이며 접미는 " +
            "1~20자 한글·영문·숫자(표정·상황·감정)입니다. 같은 인물 안에서 이름이 겹치면 409, 형식이 어긋나면 400, " +
            "인물당 10장을 넘으면 400입니다. 서버가 객체 키가 이 스토리의 업로드 경로 아래인지 확인하고 " +
            "`HEAD`로 존재·크기·형식을 재검증합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "연결 성공",
                content = [Content(schema = Schema(implementation = CharacterImageResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "이름 형식 위반·10장 초과·다른 스토리 키·형식/크기 위반, 업로드 미완료(code: UPLOAD_NOT_FOUND), 게스트 소유 스토리",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "타인 스토리 또는 정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리·인물을 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "409", description = "같은 이름의 이미지가 이미 있음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/characters/{characterId}/images")
    fun addCharacterImage(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @Parameter(description = "인물 ID(공개 식별자). 편집 폼 응답의 characters[].id")
        @PathVariable characterId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: AddCharacterImageRequest,
    ): CharacterImageResponse =
        storyImageService.addCharacterImage(storyId, characterId, requireUser(userId), request)

    @Operation(
        summary = "인물 이미지 삭제",
        description = "인물 이미지 참조를 지웁니다(KNK-1126). 없어도 204입니다(멱등). **S3 객체는 남깁니다** — " +
            "지난 채팅 본문의 이미지 마커가 그 객체를 가리키고 있어 지우면 옛 대화가 깨집니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 완료(이미지가 없던 경우 포함)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "400", description = "게스트 소유 스토리", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "타인 스토리 또는 정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리·인물을 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/characters/{characterId}/images/{imageId}")
    fun deleteCharacterImage(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @Parameter(description = "인물 ID(공개 식별자)")
        @PathVariable characterId: String,
        @Parameter(description = "이미지 ID(공개 식별자)")
        @PathVariable imageId: String,
        @CurrentUserId userId: Long?,
    ) = storyImageService.deleteCharacterImage(storyId, characterId, imageId, requireUser(userId))

    // 인증 필수 경로지만, 토큰은 유효하나 사용자가 사라진 경우 null이 올 수 있어 401로 통일한다.
    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
