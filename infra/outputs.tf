# 作ったあとに画面に見せる値（あとで RDS・EC2 の設計図や、確認に使う）
# description は、terraform output などの画面にも出る説明（TFLint の terraform_documented_outputs）

output "account_id" {
  description = "AWS アカウントの番号（プロジェクト Do the Thing）"
  value       = data.aws_caller_identity.me.account_id
}

output "vpc_id" {
  description = "VPC（自分の敷地）の名前"
  value       = aws_vpc.main.id
}

output "public_subnet_id" {
  description = "表の庭（パブリックサブネット 2a）の名前。EC2 を置く"
  value       = aws_subnet.public.id
}

output "private_subnet_ids" {
  description = "奥の部屋（プライベートサブネット 2a・2b）の名前。RDS を置く"
  value       = [aws_subnet.private_a.id, aws_subnet.private_b.id]
}

output "db_endpoint" {
  description = "RDS の住所と番号。deploy/install.sh の 1 つ目に渡す（アプリの DB_URL は jdbc:postgresql://<これ>/taskdb）"
  value       = "${aws_db_instance.main.address}:${aws_db_instance.main.port}"
}

output "db_master_secret_arn" {
  description = "RDS の合いカギをしまった金庫（Secrets Manager）の場所。パスワードそのものではない。deploy/install.sh の 2 つ目に渡す"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "ec2_instance_id" {
  description = "EC2 の名前。Session Manager の通路で入るときに使う（ssh <これ>）"
  value       = aws_instance.app.id
}

output "app_url" {
  description = "家のブラウザで開く住所（80 番。家の IP からだけ見られる。https:// ではなく http:// で開く）"
  value       = "http://${aws_instance.app.public_ip}"
}
