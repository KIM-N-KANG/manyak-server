package com.knk.manyak.story.dto

import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.image.service.UploadedImageKind
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** presign 요청(KNK-1126, 스펙 §4-3-8). 서명에 `Content-Type`·`Content-Length`를 고정하므로 둘 다 필수다. */
@Schema(description = "이미지 업로드용 presigned URL 발급 요청")
data class ImagePresignRequest(
    @field:NotNull
    @field:Schema(description = "업로드 대상. 객체 키 prefix가 갈린다", example = "COVER")
    val kind: UploadedImageKind?,

    @field:NotBlank
    @field:Schema(description = "image/jpeg · image/png · image/webp 중 하나", example = "image/webp")
    val contentType: String?,

    @field:NotNull
    @field:Min(1)
    @field:Max(5_242_880)
    @field:Schema(description = "바이트 크기(1~5,242,880). 클라이언트는 이 값 그대로 PUT해야 한다", example = "204800")
    val contentLength: Long?,
)

@Schema(description = "presigned URL 발급 응답")
data class ImagePresignResponse(
    @field:Schema(description = "이 URL로 PUT한다. 요청한 Content-Type·Content-Length 그대로 보내야 서명이 맞는다")
    val uploadUrl: String,

    @field:Schema(description = "PUT을 마친 뒤 연결 요청에 그대로 넣는 객체 키", example = "thumbnails/uploaded/3f2504e0-.../8f1c....webp")
    val objectKey: String,

    @field:Schema(description = "서명 만료(초)", example = "600")
    val expiresInSeconds: Long,
)

/** 인물 이미지 연결 요청(KNK-1126). */
@Schema(description = "인물 이미지 연결 요청")
data class AddCharacterImageRequest(
    @field:NotBlank
    @field:Schema(description = "presign으로 받은 객체 키. 이 스토리의 인물 업로드 prefix 아래여야 한다")
    val objectKey: String?,

    @field:NotBlank
    @field:Size(max = 120)
    @field:Schema(
        description = "`{인물이름}_{접미}` 형식. 접미는 1~20자 한글·영문·숫자(표정·상황·감정)이며 같은 인물 안에서 유일하다",
        example = "세린_웃음",
    )
    val imageName: String?,
)

/** 인물 이미지 한 장(KNK-1126). 편집 폼과 연결 응답이 같은 모양을 쓴다. */
@Schema(description = "인물 이미지")
data class CharacterImageResponse(
    @field:Schema(description = "이미지 ID(공개 식별자). 삭제 요청에 쓴다")
    val id: String,

    @field:Schema(description = "이미지 이름", example = "세린_웃음")
    val imageName: String,

    @field:Schema(description = "서빙 URL")
    val imageUrl: String,

    // 검수 게이트(KNK-1126, 스펙 §4-3-8). 소유자 화면에만 나가는 값이다 — 공개 노출은 APPROVED만 하고
    // 상태 자체를 싣지 않는다. 지금은 기본값이 APPROVED라 항상 APPROVED다.
    @field:Schema(description = "검수 상태(APPROVED · PENDING · REJECTED)", example = "APPROVED")
    val moderationStatus: ImageModerationStatus,
)

/** 편집 폼의 인물 항목(KNK-1126). 상세 응답의 `characters[]`와 달리 이미지 전부와 인물 식별자를 싣는다. */
@Schema(description = "편집 폼 인물(이미지 관리용)")
data class StoryEditCharacterResponse(
    @field:Schema(description = "인물 ID(공개 식별자). 이미지 연결·삭제 경로에 쓴다")
    val id: String,

    @field:Schema(description = "인물 이름", example = "세린")
    val name: String,

    @field:Schema(description = "이 인물의 이미지 목록(표시 순서)")
    val images: List<CharacterImageResponse>,
)
