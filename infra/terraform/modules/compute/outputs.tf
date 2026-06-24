output "instance_id" {
  description = "app EC2 인스턴스 ID (ALB target group 등록·SSM 접속 대상)"
  value       = aws_instance.app.id
}

output "private_ip" {
  description = "app EC2 사설 IP"
  value       = aws_instance.app.private_ip
}
