terraform {
  backend "s3" {
    # bucket, dynamodb_table는 bootstrap 출력값으로
    # `terraform init -backend-config=backend.hcl`에서 주입한다.
    key     = "prod/terraform.tfstate"
    region  = "ap-northeast-2"
    encrypt = true
  }
}
