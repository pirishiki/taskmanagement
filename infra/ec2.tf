# EC2：アプリ（Nginx ＋ Spring Boot）が住む家（イシュー #61）。作った時点から、時間ごとにお金がかかる
#
#   家のブラウザ ──80 番（家の IP だけ）──▶ EC2（表の庭）──5432 番──▶ RDS（奥の部屋）
#   手入れ：PC ──Session Manager の通路──▶ EC2（ポート 22 はどこにも開けない）

# ---------------------------------------------------------------------------
# 1. 門番（セキュリティグループ）
# ---------------------------------------------------------------------------

# EC2 専用の門番。ルールは下の aws_vpc_security_group_*_rule に 1 つずつ書く
resource "aws_security_group" "ec2" {
  name        = "${var.project_name}-ec2-sg"
  description = "App EC2. HTTP from home IP only. No SSH port (Session Manager)"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-ec2-sg"
  }
}

# 入口：80 番（画面）を、家の IP だけ通す（No.25 の2。ログインがないので、世界中には開けない）
resource "aws_vpc_security_group_ingress_rule" "ec2_http_from_home" {
  security_group_id = aws_security_group.ec2.id
  description       = "HTTP from home"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = var.home_ip_cidr
}

# 出口：443 番（HTTPS）で外へ。Session Manager・Java と Nginx のダウンロード・金庫（Secrets Manager）に使う
resource "aws_vpc_security_group_egress_rule" "ec2_https_out" {
  security_group_id = aws_security_group.ec2.id
  description       = "HTTPS out (Session Manager, packages, Secrets Manager)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# 出口：5432 番で RDS へ。行き先は IP ではなく「RDS の門番」で指定する
resource "aws_vpc_security_group_egress_rule" "ec2_to_rds" {
  security_group_id            = aws_security_group.ec2.id
  description                  = "PostgreSQL to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.rds.id
}

# RDS の門番の入口：5432 番を「EC2 の門番の名札を付けた人」だけ通す（講義の「EC2 からのみ RDS に接続」）
# 名札で指定するので、EC2 を作り直して IP が変わっても書き直さなくてよい
resource "aws_vpc_security_group_ingress_rule" "rds_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from app EC2"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.ec2.id
}

# ---------------------------------------------------------------------------
# 2. 許可証（IAM ロール）：EC2 が AWS のサービスを使ってよい範囲
# ---------------------------------------------------------------------------

# だれがこの許可証を使えるか（信頼ポリシー）：EC2 だけ
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project_name}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# 許可 1：Session Manager の管理人さんと話してよい（入り方 C）。AWS が用意している決まった許可
resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 許可 2：RDS が作った金庫（名前が rds!db- で始まる）の中を「読むだけ」
# RDS は作業の日の終わりに destroy し、作り直すと金庫の名前の後ろが変わる。
# 金庫を 1 つの ARN で指定すると、RDS を消すたびにこの許可も消え、作るたびに作り直しになるので、
# 「このアカウント・このリージョンの、RDS が作った金庫」に絞る（このアカウントの RDS はこの 1 つだけ）
data "aws_iam_policy_document" "ec2_read_db_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["arn:aws:secretsmanager:ap-southeast-2:${data.aws_caller_identity.me.account_id}:secret:rds!db-*"]
  }
}

resource "aws_iam_role_policy" "ec2_read_db_secret" {
  name   = "read-rds-master-secret"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.ec2_read_db_secret.json
}

# 許可証を EC2 に持たせるための入れ物（インスタンスプロファイル）
resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-ec2-profile"
  role = aws_iam_role.ec2.name
}

# ---------------------------------------------------------------------------
# 3. 家（EC2 本体）と、入居の日の準備リスト（user_data）
# ---------------------------------------------------------------------------

# data＝作らずに「読むだけ」。AWS が公開している、Amazon Linux 2023（arm64＝t4g 用）のいちばん新しい OS の番号
data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# ドアに取り付ける錠前（SSH の公開鍵）。鍵（秘密鍵）は PC から出さない
resource "aws_key_pair" "ec2" {
  key_name   = "${var.project_name}-ec2-key"
  public_key = file(pathexpand(var.ssh_public_key_path))
}

resource "aws_instance" "app" {
  ami           = data.aws_ssm_parameter.al2023_arm64.value
  instance_type = "t4g.micro" # 1GB。1時間 約 0.0106 USD
  key_name      = aws_key_pair.ec2.key_name

  # 置き場所：表の庭（パブリックサブネット）
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  # パブリック IP を付ける（1時間 0.005 USD）。NAT ゲートウェイを作らないので、
  # 外（Session Manager・Java と Nginx のダウンロード・金庫）と話すにはこれが要る。入口は門番が 80 番の家の IP だけに絞る
  associate_public_ip_address = true

  # 許可証（Session Manager と、RDS の金庫を読むだけ）
  iam_instance_profile = aws_iam_instance_profile.ec2.name

  # 力（CPU クレジット）を使い切ったら、ゆっくり動く。追加のお金はかからない
  credit_specification {
    cpu_credits = "standard"
  }

  # EC2 の中から自分の情報を聞くときは、合言葉（トークン）を必須にする（IMDSv2）
  metadata_options {
    http_tokens   = "required"
    http_endpoint = "enabled"
  }

  # 家の床（ルートディスク）：gp3・カギ（暗号化）あり。大きさは OS の決まりの 8GB（swap 1GB もここに作る）
  root_block_device {
    volume_type = "gp3"
    encrypted   = true
  }

  # 入居の日に 1 回だけ実行される準備リスト。アプリを置いて動かすのは、家ができたあとに入って行う
  user_data = <<-USERDATA
    #!/bin/bash
    set -euxo pipefail

    # 1. swap 1GB（作業机＝メモリがあふれたときに、床＝ディスクに一時的に置く）
    dd if=/dev/zero of=/swapfile bs=1M count=1024
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile swap swap defaults 0 0' >> /etc/fstab

    # 2. Java 21（Amazon Corretto）。アプリは Java 21 で作ってある
    dnf install -y java-21-amazon-corretto-headless

    # 3. Nginx 1.30（nginx.org の公式の配り場から。手元の Docker の nginx:1.30 とそろえる）
    cat > /etc/yum.repos.d/nginx.repo <<'REPO'
    [nginx-stable]
    name=nginx stable repo
    baseurl=https://nginx.org/packages/amzn/2023/$basearch/
    gpgcheck=1
    enabled=1
    gpgkey=https://nginx.org/keys/nginx_signing.key
    module_hotfixes=true
    REPO
    dnf install -y 'nginx-1.30.*'
    systemctl enable --now nginx
  USERDATA

  # AWS が新しい OS を出すたびに「作り直し」になるのを防ぐ（OS を新しくするときは、自分で決めて作り直す）
  lifecycle {
    ignore_changes = [ami]
  }

  tags = {
    Name = "${var.project_name}-app"
  }
}
