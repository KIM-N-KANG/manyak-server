output "db_instance_endpoint" {
  description = "RDS 엔드포인트 (host:port)"
  value       = aws_db_instance.postgres.endpoint
}

output "db_address" {
  description = "RDS 호스트 주소"
  value       = aws_db_instance.postgres.address
}

output "db_port" {
  description = "RDS 포트"
  value       = aws_db_instance.postgres.port
}

output "db_name" {
  description = "초기 데이터베이스 이름"
  value       = aws_db_instance.postgres.db_name
}

output "db_master_user_secret_arn" {
  description = "RDS 마스터 자격증명 Secrets Manager ARN (앱 시크릿 주입에 사용 — KNK-241)"
  value       = aws_db_instance.postgres.master_user_secret[0].secret_arn
}

output "redis_endpoint" {
  description = "Redis 엔드포인트 주소 (enable_redis=true 일 때)"
  value       = length(aws_elasticache_cluster.redis) > 0 ? aws_elasticache_cluster.redis[0].cache_nodes[0].address : null
}

output "redis_port" {
  description = "Redis 포트 (enable_redis=true 일 때)"
  value       = length(aws_elasticache_cluster.redis) > 0 ? aws_elasticache_cluster.redis[0].port : null
}
