terraform {
  required_version = ">= 1.10" # S3 native locking(use_lockfile)은 1.10+

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}
