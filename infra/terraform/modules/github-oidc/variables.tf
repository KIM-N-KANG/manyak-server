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
  # GitHub OIDC sub 클레임은 레포의 정규(canonical) 대소문자를 그대로 전달하고 IAM StringLike는 대소문자를 구분한다.
  # 따라서 GitHub 실제 표기(KIM-N-KANG)와 정확히 일치해야 한다. 소문자로 두면 sts:AssumeRoleWithWebIdentity 가 거부된다.
  # (GHCR 이미지 경로의 소문자 kim-n-kang 과는 무관 — 레지스트리 경로는 소문자 강제)
  default = "KIM-N-KANG"
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

variable "deploy_instance_ids" {
  description = "deploy(ssm:SendCommand) 대상 EC2 인스턴스 ID 목록. 비우면 Project 태그 조건의 instance/* 로 폴백 (KNK-241)"
  type        = list(string)
  default     = []
}
