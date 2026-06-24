output "app_secret_arn" {
  description = "앱 런타임 시크릿 Secrets Manager ARN (EC2 IAM 읽기 권한·user-data 주입에 사용)"
  value       = aws_secretsmanager_secret.app.arn
}

output "app_secret_name" {
  description = "앱 시크릿 이름 (콘솔/CLI 로 실제 값 입력 시 참조)"
  value       = aws_secretsmanager_secret.app.name
}
