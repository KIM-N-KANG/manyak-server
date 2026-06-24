data "aws_caller_identity" "current" {}

locals {
  state_bucket_name = var.state_bucket_name != "" ? var.state_bucket_name : "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}"
}

# 원격 state 저장용 S3 버킷 (잠금은 S3 네이티브 use_lockfile 사용 → 별도 DynamoDB 불필요)
resource "aws_s3_bucket" "tfstate" {
  bucket = local.state_bucket_name
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
