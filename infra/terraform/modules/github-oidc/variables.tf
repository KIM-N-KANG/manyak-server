variable "project" {
  description = "프로젝트 식별자 (태그)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그)"
  type        = string
}

variable "create_oidc_provider" {
  description = "GitHub OIDC provider를 생성할지 여부. 계정에 이미 있으면 false 로 두고 oidc_provider_arn 를 전달"
  type        = bool
  default     = true
}

variable "github_owner" {
  description = "GitHub 소유자(org/user)"
  type        = string
  default     = "kim-n-kang"
}

variable "github_repo" {
  description = "ECR 푸시를 허용할 GitHub 레포지토리 이름"
  type        = string
  default     = "manyak-server"
}

variable "allowed_branches" {
  description = "역할 assume 를 허용할 브랜치 목록. 실제 배포는 main 만"
  type        = list(string)
  default     = ["main"]
}

variable "ecr_repository_arns" {
  description = "푸시를 허용할 ECR 레포지토리 ARN 목록"
  type        = list(string)
}

variable "role_name" {
  description = "CI 역할 이름. 비우면 {project}-{environment}-gha-ecr-push"
  type        = string
  default     = ""
}
