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
