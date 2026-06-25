variable "project" {
  description = "프로젝트 이름 (리소스 태그·네이밍 prefix)"
  type        = string
}

variable "environment" {
  description = "환경 이름 (prod 등)"
  type        = string
}
