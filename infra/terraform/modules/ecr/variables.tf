variable "project" {
  description = "프로젝트 식별자 (태그)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그)"
  type        = string
}

variable "repository_names" {
  description = "생성할 ECR 레포지토리 이름 목록. server 중심이며 필요 시 manyak-ai 등으로 확장"
  type        = list(string)
  default     = ["manyak-server"]
}

variable "image_tag_mutability" {
  description = "이미지 태그 변경 가능 여부. latest 재푸시를 위해 MUTABLE"
  type        = string
  default     = "MUTABLE"

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.image_tag_mutability)
    error_message = "image_tag_mutability 는 MUTABLE 또는 IMMUTABLE 이어야 합니다."
  }
}

variable "scan_on_push" {
  description = "푸시 시 이미지 취약점 스캔 활성화"
  type        = bool
  default     = true
}

variable "max_image_count" {
  description = "레포지토리당 보관할 태그 이미지 최대 개수 (초과분 정리)"
  type        = number
  default     = 10
}

variable "untagged_expire_days" {
  description = "태그 없는 이미지를 만료시킬 경과 일수"
  type        = number
  default     = 7
}

variable "force_delete" {
  description = "이미지가 남아 있어도 레포지토리 삭제 허용 (MVP 정리 편의)"
  type        = bool
  default     = false
}
