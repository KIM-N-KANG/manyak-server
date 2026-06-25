variable "project" {
  description = "프로젝트 식별자 (태그·리소스 접두)"
  type        = string
}

variable "environment" {
  description = "배포 환경 (태그·리소스 접두)"
  type        = string
}

variable "vpc_id" {
  description = "target group을 둘 VPC ID (network vpc_id)"
  type        = string
}

variable "public_subnet_ids" {
  description = "ALB를 배치할 public 서브넷 ID 목록 (2 AZ, network public_subnet_ids)"
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "ALB에 연결할 보안 그룹 ID (security alb_security_group_id)"
  type        = string
}

variable "ec2_instance_id" {
  description = "target group에 등록할 app EC2 인스턴스 ID (compute instance_id)"
  type        = string
}

variable "domain" {
  description = "백엔드 도메인 (ACM 인증서·HTTPS 대상)"
  type        = string
  default     = "api.manyak.app"
}

variable "cloudflare_zone_name" {
  description = "Cloudflare에서 관리하는 루트 존 이름"
  type        = string
  default     = "manyak.app"
}

variable "api_subdomain" {
  description = "ALB로 연결할 서브도메인 레코드 이름 (zone 기준)"
  type        = string
  default     = "api"
}
