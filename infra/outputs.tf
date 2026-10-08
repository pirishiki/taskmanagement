# 作ったあとに画面に見せる値（あとで RDS・EC2 の設計図や、確認に使う）

output "account_id" {
  value = data.aws_caller_identity.me.account_id
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_id" {
  value = aws_subnet.public.id
}

output "private_subnet_ids" {
  value = [aws_subnet.private_a.id, aws_subnet.private_b.id]
}

# RDS の接続先（住所と番号）。アプリの DB_URL に使う：jdbc:postgresql://<住所>:<番号>/taskdb
output "db_endpoint" {
  value = "${aws_db_instance.main.address}:${aws_db_instance.main.port}"
}

# パスワードをしまった金庫（Secrets Manager）の場所。パスワードそのものではない
output "db_master_secret_arn" {
  value = aws_db_instance.main.master_user_secret[0].secret_arn
}
