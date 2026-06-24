# Terraform 모듈

재사용 가능한 인프라 모듈을 둔다. 후속 서브태스크에서 추가한다.

| 모듈 | 서브태스크 | 내용 |
|------|-----------|------|
| `ecr` | KNK-236 | 컨테이너 이미지 레포 |
| `github-oidc` | KNK-236 | GitHub Actions OIDC + ECR 푸시 IAM role |
| `network` | KNK-237 | VPC 3계층(public/app/db)·IGW·NAT(MVP 단일)·route·S3 Gateway Endpoint |
| `security` | KNK-238 | SG 체인(alb→app→rds/redis) + EC2 IAM(SSM·ECR pull·CloudWatch, SSH 미개방) |
| `data` | KNK-239 | RDS PostgreSQL(단일AZ·자동백업·Secrets Manager) + ElastiCache Redis(토글) + DB subnet group(2AZ) |
| `compute` | KNK-240 | EC2 + SSM + user-data(compose) |
| `edge` | KNK-240 | ALB + ACM + Route53 |
