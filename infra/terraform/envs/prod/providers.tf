provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# Cloudflare(ACM 검증·api.manyak.app 레코드). 토큰은 변수로 주입하며 커밋하지 않는다.
provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
