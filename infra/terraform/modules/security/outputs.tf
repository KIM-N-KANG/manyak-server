output "alb_security_group_id" {
  description = "ALB 보안 그룹 ID (edge 모듈이 참조)"
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "App(EC2) 보안 그룹 ID (compute 모듈이 참조)"
  value       = aws_security_group.app.id
}

output "rds_security_group_id" {
  description = "RDS 보안 그룹 ID (data 모듈이 참조)"
  value       = aws_security_group.rds.id
}

output "redis_security_group_id" {
  description = "Redis 보안 그룹 ID (data 모듈이 참조)"
  value       = aws_security_group.redis.id
}

output "ec2_role_arn" {
  description = "EC2 인스턴스 역할 ARN"
  value       = aws_iam_role.ec2.arn
}

output "ec2_role_name" {
  description = "EC2 인스턴스 역할 이름"
  value       = aws_iam_role.ec2.name
}

output "instance_profile_name" {
  description = "EC2 인스턴스 프로파일 이름 (compute 모듈이 EC2에 부착)"
  value       = aws_iam_instance_profile.ec2.name
}

output "instance_profile_arn" {
  description = "EC2 인스턴스 프로파일 ARN"
  value       = aws_iam_instance_profile.ec2.arn
}
