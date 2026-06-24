variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
  default     = "manyak"
}

variable "environment" {
  description = "배포 환경"
  type        = string
  default     = "prod"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "create_github_oidc_provider" {
  description = "GitHub OIDC provider 생성 여부. 계정에 이미 존재하면 false"
  type        = bool
  default     = true
}

variable "enable_redis" {
  description = "ElastiCache Redis 생성 여부 (KNK-239 data 모듈 토글)"
  type        = bool
  default     = true
}
