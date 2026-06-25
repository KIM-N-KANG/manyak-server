# GitHub Actions가 장기 키 없이 AWS에 인증하도록 OIDC 신뢰를 구성한다.
# main 브랜치 워크플로만 이 역할을 assume 해 ECR 에 푸시할 수 있다.

# OIDC provider 의 thumbprint 를 런타임에 취득 (하드코딩 회피)
data "tls_certificate" "github" {
  count = var.create_oidc_provider ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github[0].certificates[0].sha1_fingerprint]

  tags = {
    Name = "github-actions-oidc"
  }
}

# 생성하지 않는 경우, 계정에 이미 있는 provider 를 조회해 ARN 을 얻는다
data "aws_iam_openid_connect_provider" "existing" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.existing[0].arn
  role_name         = coalesce(var.role_name, "${var.project}-${var.environment}-gha-ecr-push")
  subjects          = [for branch in var.allowed_branches : "repo:${var.github_owner}/${var.github_repo}:ref:refs/heads/${branch}"]

  # KNK-241 deploy 대상 인스턴스 ARN. 지정 시 해당 인스턴스로만 SendCommand 제한(폭발 반경 축소), 비우면 instance/* 폴백.
  deploy_instance_arns = length(var.deploy_instance_ids) > 0 ? [
    for id in var.deploy_instance_ids : "arn:aws:ec2:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:instance/${id}"
  ] : ["arn:aws:ec2:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:instance/*"]
}

# OIDC 토큰으로만 assume, 지정 레포+브랜치(sub)로 제한
data "aws_iam_policy_document" "assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.subjects
    }
  }
}

resource "aws_iam_role" "ci" {
  name               = local.role_name
  description        = "Role assumed by GitHub Actions (OIDC) to push images to ECR and run SSM deploy"
  assume_role_policy = data.aws_iam_policy_document.assume.json

  tags = {
    Name = local.role_name
  }
}

# 최소 권한: 토큰 발급(전역) + 지정 레포로의 푸시/풀
data "aws_iam_policy_document" "ecr_push" {
  statement {
    sid       = "EcrAuthToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPushPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = var.ecr_repository_arns
  }
}

resource "aws_iam_role_policy" "ecr_push" {
  name   = "ecr-push"
  role   = aws_iam_role.ci.id
  policy = data.aws_iam_policy_document.ecr_push.json
}

# ── 배포(deploy) 권한: main 워크플로가 SSM Run Command 로 EC2에 compose 재기동 (KNK-241) ──
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "deploy" {
  # 대상 EC2로의 SendCommand: Project 태그(provider default_tags)로 제한
  statement {
    sid       = "SsmSendCommandInstance"
    effect    = "Allow"
    actions   = ["ssm:SendCommand"]
    resources = local.deploy_instance_arns
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/Project"
      values   = [var.project]
    }
  }
  # 실행 문서: 기본은 범용 AWS-RunShellScript, ssm_document_arns 지정 시 그 전용 문서로만 제한(KNK-260).
  statement {
    sid       = "SsmSendCommandDocument"
    effect    = "Allow"
    actions   = ["ssm:SendCommand"]
    resources = var.ssm_document_arns != null ? var.ssm_document_arns : ["arn:aws:ssm:${data.aws_region.current.name}::document/AWS-RunShellScript"]
  }
  # 명령 결과 폴링 + 대상 인스턴스 조회
  statement {
    sid    = "SsmStatusAndDescribe"
    effect = "Allow"
    actions = [
      "ssm:GetCommandInvocation",
      "ssm:ListCommandInvocations",
      "ec2:DescribeInstances",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "ssm-deploy"
  role   = aws_iam_role.ci.id
  policy = data.aws_iam_policy_document.deploy.json
}
