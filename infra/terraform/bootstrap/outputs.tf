output "state_bucket_name" {
  description = "envs/*/backend.hcl의 bucket 값"
  value       = aws_s3_bucket.tfstate.id
}

output "region" {
  description = "backend region"
  value       = var.region
}
