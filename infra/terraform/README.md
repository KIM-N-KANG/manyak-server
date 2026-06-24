# manyak 인프라 (Terraform)

마냑 백엔드 AWS 인프라를 Terraform으로 관리한다. (상위 작업: KNK-233)

## 구조

```
infra/terraform/
  bootstrap/      # 1회성: 원격 state 백엔드(S3, 잠금은 S3 네이티브 use_lockfile) 생성 (로컬 state)
  envs/prod/      # 운영 환경 구성 (S3 backend 사용)
  modules/        # 재사용 모듈 (ecr·github-oidc·network·security 완료, data/compute/edge 후속)
```

## 사전 요구

- Terraform >= 1.10 (prod backend의 S3 native locking `use_lockfile`이 1.10+ 필요)
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

출력된 `state_bucket_name`을 다음 단계(`backend.hcl`)에서 사용한다.

## 2) 운영 환경 (envs/prod)

```bash
cd infra/terraform/envs/prod
cp backend.hcl.example backend.hcl             # bootstrap 출력값으로 채움
cp terraform.tfvars.example terraform.tfvars   # 필요 시 수정
terraform init -backend-config=backend.hcl
terraform plan
terraform apply
```

## 3) 이미지 빌드·푸시 (KNK-236)

브랜치별로 레지스트리를 분리한다. 워크플로: `.github/workflows/docker-image.yml`

| 트리거 | 레지스트리 | 태그 |
|--------|-----------|------|
| `dev` push | GHCR `ghcr.io/kim-n-kang/manyak-server` | `dev`, `<short-sha>` |
| `main` push | ECR `manyak-server` | `latest`, `<short-sha>` |

`main` 푸시는 OIDC로 AWS에 인증해 ECR에 푸시한다(장기 키 미보관). `dev`는 기존 GHCR 방식을 유지한다.

### 사전 설정 (apply 후 1회)

1. `envs/prod`에서 `terraform apply` 후 CI 역할 ARN을 확인한다.

   ```bash
   cd infra/terraform/envs/prod
   terraform output -raw github_actions_ci_role_arn
   ```

2. GitHub 레포 **Settings → Secrets and variables → Actions → Variables**에 추가한다.
   - `AWS_ROLE_ARN` = 위 ARN

   계정에 GitHub OIDC provider(`token.actions.githubusercontent.com`)가 이미 있으면,
   `terraform.tfvars`에 `create_github_oidc_provider = false`를 두고 apply 한다.

### 배포 후 확인

`main` 푸시 → 워크플로 성공 후 ECR에 이미지가 올라왔는지 확인한다.

```bash
aws ecr describe-images \
  --repository-name manyak-server \
  --region ap-northeast-2 \
  --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags'
```

## 검증 (오프라인, 자격증명 불필요)

```bash
terraform fmt -recursive -check
cd infra/terraform/envs/prod && terraform init -backend=false && terraform validate
cd infra/terraform/bootstrap && terraform init -backend=false && terraform validate
```

## 비고

- `.terraform.lock.hcl`은 커밋한다(프로바이더 버전 고정). `*.tfstate`·`*.tfvars`·`backend.hcl`은 커밋하지 않는다(.gitignore).
- 리소스 모듈: ECR·github-oidc(KNK-236)·network(KNK-237)·security(KNK-238, SG 체인+IAM) 완료. RDS/EC2/ALB는 KNK-239~240에서 `modules/`에 추가한다.
