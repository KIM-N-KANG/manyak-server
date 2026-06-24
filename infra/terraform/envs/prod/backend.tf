terraform {
  backend "s3" {
    # bucket은 bootstrap 출력값으로 `terraform init -backend-config=backend.hcl`에서 주입한다.
    key          = "prod/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true # S3 네이티브 state 잠금 (별도 DynamoDB 불필요)
  }
}
