variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
  default     = "manyak"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket_name" {
  description = "Terraform 원격 state용 S3 버킷 이름 (전역 고유). 빈 값이면 \"<project>-tfstate-<account_id>\"로 생성."
  type        = string
  default     = ""
}
