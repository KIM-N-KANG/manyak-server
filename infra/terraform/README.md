# manyak 인프라 (Terraform)

마냑 백엔드 AWS 인프라를 Terraform으로 관리한다. (상위 작업: KNK-233)

## 구조

```
infra/terraform/
  bootstrap/      # 1회성: 원격 state 백엔드(S3, 잠금은 S3 네이티브 use_lockfile) 생성 (로컬 state)
  envs/prod/      # 운영 환경 구성 (S3 backend 사용)
  modules/        # 재사용 모듈 (ecr·github-oidc·network·security·data·compute·edge 완료)
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

## 4) 배포 + 시크릿 (KNK-241)

### 앱 시크릿 입력 (apply 후 1회)

`secrets` 모듈은 `manyak/prod/app` 시크릿 그릇만 만든다(빈 값, `ignore_changes`). 실제 값을 입력한다:

```bash
aws secretsmanager put-secret-value \
  --secret-id manyak/prod/app --region ap-northeast-2 \
  --secret-string '{"SERVER_SENTRY_DSN":"<백엔드 Sentry DSN>","AI_SENTRY_DSN":"","MANYAK_SLACK_FEEDBACK_WEBHOOK_URL":"<Slack webhook>","MANYAK_ANALYTICS_ANONYMOUS_ID_PEPPER":"<임의 난수: openssl rand -hex 32>"}'
```

DB 자격증명(`MANYAK_DB_*`)은 RDS가 자동 관리하는 secret에서 user-data가 읽어 주입하므로 별도 입력이 필요 없다.

> ⚠️ 시크릿 값을 변경한 뒤에는 **재배포해야 `.env`에 반영된다**(`deploy.sh`가 매 실행마다 Secrets Manager를 재조회). `main` 재푸시 또는 SSM Run Command로 `bash /opt/manyak/deploy.sh` 재실행.

### 배포 (자동)

`main` 푸시 → `.github/workflows/docker-image.yml`:

1. **build 잡**: 테스트 → 이미지 빌드 → ECR push(`manyak-server:latest`, `<short-sha>`)
2. **deploy 잡**: SSM Run Command 로 EC2의 `/opt/manyak/deploy.sh` 실행(ECR 재로그인 → `docker compose pull` → `up -d`) → `https://api.manyak.app/actuator/health` 스모크

> EC2 IAM 은 `secretsmanager:GetSecretValue`(RDS+앱 secret)·SSM·ECR pull 권한을, GitHub OIDC 역할은 `ssm:SendCommand`(Project 태그 제한) 권한을 가진다.

수동 검증: `http/smoke-prod.http` (ALB 타깃 healthy 까지 2~5분 후).

### 롤백

ECR 에 직전 `<short-sha>` 태그가 보존되므로 이전 이미지로 되돌릴 수 있다.

```bash
# 대상 EC2 에서(SSM Session Manager 접속): /opt/manyak/.env 의 SERVER_IMAGE 를 직전 정상 <short-sha> 로 교체 후
cd /opt/manyak && docker compose pull && docker compose up -d
```

또는 직전 정상 커밋을 `main` 에 다시 머지해 재배포한다. Flyway 마이그레이션은 전진 전용이므로, 스키마 변경 롤백은 보상(역) 마이그레이션으로 처리한다.

## 검증 (오프라인, 자격증명 불필요)

```bash
terraform fmt -recursive -check
cd infra/terraform/envs/prod && terraform init -backend=false && terraform validate
cd infra/terraform/bootstrap && terraform init -backend=false && terraform validate
```

## 비고

- `.terraform.lock.hcl`은 커밋한다(프로바이더 버전 고정). `*.tfstate`·`*.tfvars`·`backend.hcl`은 커밋하지 않는다(.gitignore).
- 리소스 모듈: ECR·github-oidc(KNK-236)·network(KNK-237)·security(KNK-238)·data(KNK-239)·compute+edge(KNK-240, EC2+ALB/ACM/Cloudflare) 완료. KNK-241에서 시크릿 주입·배포로 마무리한다.
- DNS는 Cloudflare(manyak.app). edge 모듈이 cloudflare provider로 ACM 검증·`api.manyak.app` 레코드를 생성하며, `cloudflare_api_token`을 tfvars/환경변수로 주입한다(커밋 금지).
