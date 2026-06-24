output "state_bucket_name" {
  description = "envs/*/backend.hcl의 bucket 값"
  value       = aws_s3_bucket.tfstate.id
}

output "lock_table_name" {
  description = "envs/*/backend.hcl의 dynamodb_table 값"
  value       = aws_dynamodb_table.tflock.name
}

output "region" {
  description = "backend region"
  value       = var.region
}
