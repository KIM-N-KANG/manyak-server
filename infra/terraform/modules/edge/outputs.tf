output "alb_dns_name" {
  description = "ALB DNS 이름 (Cloudflare CNAME 대상)"
  value       = aws_lb.this.dns_name
}

output "alb_arn" {
  description = "ALB ARN"
  value       = aws_lb.this.arn
}

output "certificate_arn" {
  description = "ACM 인증서 ARN (검증 완료)"
  value       = aws_acm_certificate_validation.this.certificate_arn
}

output "api_url" {
  description = "백엔드 HTTPS URL"
  value       = "https://${var.domain}"
}
