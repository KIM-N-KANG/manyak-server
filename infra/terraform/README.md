# manyak 인프라 (Terraform)

마냑 백엔드 AWS 인프라를 Terraform으로 관리한다. (상위 작업: KNK-233)

## 구조

```
infra/terraform/
  bootstrap/      # 1회성: 원격 state 백엔드(S3 + DynamoDB lock) 생성 (로컬 state)
  envs/prod/      # 운영 환경 구성 (S3 backend 사용)
  modules/        # 재사용 모듈 (ecr/network/security/data/compute/edge — 후속 서브태스크에서 추가)
```

## 사전 요구

- Terraform >= 1.9
- AWS 자격증명 (`aws configure` 또는 환경변수). 부트스트랩은 S3/DynamoDB 생성 권한 필요
- 리전: ap-northeast-2

## 1) 부트스트랩 (최초 1회)

원격 state 백엔드를 만든다. 이 단계만 로컬 state를 사용한다.

```bash
cd infra/terraform/bootstrap
cp terraform.tfvars.example terraform.tfvars   # 필요 시 수정
terraform init
terraform apply
```

출력된 `state_bucket_name`, `lock_table_name`을 다음 단계에서 사용한다.

## 2) 운영 환경 (envs/prod)

```bash
cd infra/terraform/envs/prod
cp backend.hcl.example backend.hcl             # bootstrap 출력값으로 채움
cp terraform.tfvars.example terraform.tfvars   # 필요 시 수정
terraform init -backend-config=backend.hcl
terraform plan
terraform apply
```

## 검증 (오프라인, 자격증명 불필요)

```bash
terraform fmt -recursive -check
cd infra/terraform/envs/prod && terraform init -backend=false && terraform validate
cd infra/terraform/bootstrap && terraform init -backend=false && terraform validate
```

## 비고

- `.terraform.lock.hcl`은 커밋한다(프로바이더 버전 고정). `*.tfstate`·`*.tfvars`·`backend.hcl`은 커밋하지 않는다(.gitignore).
- 실제 리소스 모듈(ECR/VPC/SG/RDS/EC2/ALB)은 KNK-236~240에서 `modules/`에 추가한다.
