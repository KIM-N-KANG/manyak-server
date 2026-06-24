variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그·리소스 접두)"
  type        = string
}

variable "vpc_id" {
  description = "보안 그룹을 생성할 VPC ID (network 모듈의 vpc_id 출력)"
  type        = string
}

variable "ecr_repository_arns" {
  description = "EC2가 pull할 ECR 레포지토리 ARN 목록 (ecr 모듈의 repository_arns 출력). 최소권한 ECR read 스코핑에 사용"
  type        = list(string)
}
