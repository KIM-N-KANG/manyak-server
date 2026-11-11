package com.knk.manyak.image.dto

/**
 * 팀 제작 이미지 자산의 종류. `prefix`는 S3 객체 키와 서빙 URL의 경로 조각이다(스펙 §4-3-9).
 */
enum class ImagePresetType(val prefix: String) {
    THUMBNAIL("thumbnails"),
    BACKGROUND("backgrounds"),
    CHARACTER("characters"),
}
