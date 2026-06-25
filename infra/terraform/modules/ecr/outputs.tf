output "repository_urls" {
  description = "레포지토리 이름 -> URL (CI 푸시 대상)"
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

output "repository_arns" {
  description = "레포지토리 이름 -> ARN (IAM 정책 제한용)"
  value       = { for name, repo in aws_ecr_repository.this : name => repo.arn }
}
