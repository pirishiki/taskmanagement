# RDS：AWS が面倒を見てくれる PostgreSQL（イシュー #59）。作った時点から、時間ごとにお金がかかる
#
#   VPC
#   ├─ パブリックサブネット 2a　… EC2 を置く（まだない）
#   ├─ プライベートサブネット 2a ─┐
#   └─ プライベートサブネット 2b ─┴─ DB サブネットグループ ── RDS（門番：今はだれも通さない）
#
# 練習なので、お金がかからないことを一番にした。作業の日の終わりに RDS だけ destroy し、次の作業の日に apply する
#   terraform destroy -target=aws_db_instance.main
# （本番なら、消すときのバックアップは必ず残し、動かしっぱなしにする）

# RDS を置いてよい場所のリスト。RDS の決まりで、2 つ以上のデータセンター（2a・2b）が必要
resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnets"
  subnet_ids = [aws_subnet.private_a.id, aws_subnet.private_b.id]

  tags = {
    Name = "${var.project_name}-db-subnets"
  }
}

# RDS 専用の門番。ingress（入口）も egress（出口）も書かない＝全部止める
# 通す相手（EC2）がまだないので、今はだれも通さない。EC2 の段階で「EC2 から 5432 番だけ」を足す
resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "RDS for PostgreSQL. Ingress only from the app EC2 (added later)"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.project_name}-rds-sg"
  }
}

resource "aws_db_instance" "main" {
  identifier = "${var.project_name}-db"

  # 手元の Docker と同じ PostgreSQL 17（書かないと、新しい 18 が選ばれる）。17 の中の細かい版は AWS が新しくしてくれる
  engine                     = "postgres"
  engine_version             = "17"
  auto_minor_version_upgrade = true

  # ① 大きさ：いちばん小さい厨房（メモリ 1GB）。約 0.025 USD／時間
  instance_class = "db.t4g.micro"

  # ② ディスク：gp3・20GB（いちばん小さい）・カギ（暗号化）あり。約 2.8 USD／月
  # max_allocated_storage を書かない＝自動で大きくしない（ディスクは、大きくはできても小さくはできない）
  storage_type      = "gp3"
  allocated_storage = 20
  storage_encrypted = true

  # 最初に作るデータベースの名前と、マスターユーザー（いちばん強い権限の人）の名前。手元の Docker とそろえる
  db_name  = "taskdb"
  username = "postgres"

  # ③ パスワード：Terraform に書かない。AWS が作って、AWS の金庫（Secrets Manager）にしまう
  # 台帳（terraform.tfstate）には、金庫の場所だけが残り、パスワードの文字は残らない
  manage_master_user_password = true

  # 置き場所：奥の部屋（プライベートサブネット）。インターネットから見える名前を付けない
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  # ④ バックアップ：ふだんの自動の写真は 1 日分（20GB までは無料）。消すときは写真を残さない
  backup_retention_period = 1
  skip_final_snapshot     = true
  # 練習なので、消すのを止めるカギ（削除防止）はかけない。本番では true にする
  deletion_protection = false

  tags = {
    Name = "${var.project_name}-db"
  }
}
