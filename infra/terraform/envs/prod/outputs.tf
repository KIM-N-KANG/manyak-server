output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "public 서브넷 ID 목록 (ALB 용)"
  value       = module.network.public_subnet_ids
}

output "app_subnet_ids" {
  description = "private app 서브넷 ID 목록 (EC2 용)"
  value       = module.network.app_subnet_ids
}

output "db_subnet_ids" {
  description = "private db 서브넷 ID 목록 (RDS DB subnet group 용)"
  value       = module.network.db_subnet_ids
}

output "security_group_ids" {
  description = "보안 그룹 ID (alb/app/rds/redis)"
  value = {
    alb   = module.security.alb_security_group_id
    app   = module.security.app_security_group_id
    rds   = module.security.rds_security_group_id
    redis = module.security.redis_security_group_id
  }
}

output "instance_profile_name" {
  description = "EC2 인스턴스 프로파일 이름"
  value       = module.security.instance_profile_name
}

output "db_instance_endpoint" {
  description = "RDS 엔드포인트 (host:port)"
  value       = module.data.db_instance_endpoint
}

output "db_master_user_secret_arn" {
  description = "RDS 마스터 자격증명 Secrets Manager ARN (KNK-241 앱 시크릿 주입)"
  value       = module.data.db_master_user_secret_arn
}

output "redis_endpoint" {
  description = "Redis 엔드포인트 (enable_redis=true 일 때)"
  value       = module.data.redis_endpoint
}

output "ecr_repository_urls" {
  description = "ECR 레포지토리 URL (CI 푸시 대상)"
  value       = module.ecr.repository_urls
}

output "github_actions_ci_role_arn" {
  description = "GitHub Actions 변수 AWS_ROLE_ARN 에 설정할 IAM role ARN"
  value       = module.github_oidc.ci_role_arn
}
