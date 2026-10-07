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
