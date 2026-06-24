output "ci_role_arn" {
  description = "GitHub Actions 변수 AWS_ROLE_ARN 에 설정할 IAM role ARN"
  value       = aws_iam_role.ci.arn
}

output "oidc_provider_arn" {
  description = "사용된 GitHub OIDC provider ARN"
  value       = local.oidc_provider_arn
}
