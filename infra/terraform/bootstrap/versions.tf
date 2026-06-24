terraform {
  required_version = ">= 1.10" # 프로젝트 공통(envs/prod use_lockfile 1.10+)과 정렬

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}
