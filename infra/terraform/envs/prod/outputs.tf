output "ecr_repository_urls" {
  description = "ECR 레포지토리 URL (CI 푸시 대상)"
  value       = module.ecr.repository_urls
}

output "github_actions_ci_role_arn" {
  description = "GitHub Actions 변수 AWS_ROLE_ARN 에 설정할 IAM role ARN"
  value       = module.github_oidc.ci_role_arn
}
