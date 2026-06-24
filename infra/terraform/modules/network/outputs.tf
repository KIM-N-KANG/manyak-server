output "vpc_id" {
  description = "VPC ID (SG·RDS·EC2 등이 참조)"
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR 블록"
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "public 서브넷 ID 목록 (인덱스 0=2a, 1=2c). ALB 가 참조"
  value       = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  description = "private app 서브넷 ID 목록 (0=2a 가동, 1=2c 예비). EC2 가 참조"
  value       = aws_subnet.app[*].id
}

output "db_subnet_ids" {
  description = "private db 서브넷 ID 목록. RDS DB subnet group 이 2 AZ 로 참조"
  value       = aws_subnet.db[*].id
}

output "availability_zones" {
  description = "서브넷 배치 AZ 목록"
  value       = var.azs
}

output "nat_gateway_ids" {
  description = "NAT Gateway ID 목록 (단일이면 1개)"
  value       = aws_nat_gateway.this[*].id
}

output "internet_gateway_id" {
  description = "Internet Gateway ID"
  value       = aws_internet_gateway.this.id
}
