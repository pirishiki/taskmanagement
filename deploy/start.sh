#!/bin/bash
# EC2 でアプリ（Spring Boot）を起動する手順（イシュー #61）。taskmanagement.service が実行する
#
# 起動の直前に、RDS の合いカギ（パスワード）を金庫（Secrets Manager）から受け取って、環境変数でアプリに渡す
#   ・合いカギはファイルに書かない（アプリに渡すだけ）
#   ・金庫の合いカギは 7 日ごとに作り直される。つながらなくなったら、アプリを再起動すれば受け取り直す
#     sudo systemctl restart taskmanagement
#
# 使う値（/etc/taskmanagement/app.env。install.sh が作る）
#   DB_HOST       … RDS の住所と番号（terraform output db_endpoint）
#   DB_SECRET_ARN … 金庫の場所（terraform output db_master_secret_arn）。パスワードそのものではない

# set -x（実行したコマンドを全部表示する）は使わない。合いカギが記録に残ってしまうため
set -euo pipefail

: "${DB_HOST:?DB_HOST がありません（/etc/taskmanagement/app.env）}"
: "${DB_SECRET_ARN:?DB_SECRET_ARN がありません（/etc/taskmanagement/app.env）}"

# 金庫から合いカギを受け取る（EC2 の許可証で、RDS が作った金庫だけ読める）
secret=$(aws secretsmanager get-secret-value \
  --region ap-southeast-2 \
  --secret-id "$DB_SECRET_ARN" \
  --query SecretString --output text)

# 受け取った中身は {"username": "...", "password": "..."} の形。1 つずつ取り出す
DB_USERNAME=$(python3 -c 'import json, sys; print(json.load(sys.stdin)["username"])' <<<"$secret")
DB_PASSWORD=$(python3 -c 'import json, sys; print(json.load(sys.stdin)["password"])' <<<"$secret")
unset secret

# アプリが読む環境変数（#55 で決めた名前）
export DB_URL="jdbc:postgresql://${DB_HOST}/taskdb"
export DB_USERNAME DB_PASSWORD
# アプリは同じ家の中の Nginx からだけ呼ばれるので、家の外（8080 番）には返事をしない
export SERVER_ADDRESS=127.0.0.1

# EC2 は 1GB しかないので、Java が使うメモリ（作業机）の上限を 384MB にする
exec java -Xms128m -Xmx384m -jar /opt/taskmanagement/app.jar
