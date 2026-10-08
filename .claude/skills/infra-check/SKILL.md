---
name: infra-check
description: taskmanagement の AWS の設計図（infra/ の Terraform）とデプロイの手順（deploy/ のシェル）に、品質チェックの道具（terraform fmt・validate、TFLint、Trivy、ShellCheck）を手元でかけ、指摘を 1 つずつ説明して「直す」か「わざと（理由を書いて止める）」かをユーザーと決める手順。GitHub Actions の必須のチェック infra と同じ中身を、PR を出す前に手元で動かす。AWS には何も作らない（ファイルを読むだけ）。「品質チェックをして」「Terraform をチェックして」「tflint」「trivy」「shellcheck」「infra のチェックが落ちた」「PR を出す前に確かめたい」など、infra/ や deploy/ を変えたとき、または CI の infra が失敗したときは、必ずこのスキルを使うこと。
---

# AWS の設計図とデプロイの手順の品質チェック（infra-check）

イシュー #63 で決めた品質チェックを、手元で同じように動かす。GitHub Actions の `infra（Terraform・TFLint・Trivy・ShellCheck）`（`.github/workflows/ci.yml`）と同じ道具・同じ版・同じ設定を使う。

ユーザーはプログラミング初心者。指摘は英語のまま出さず、**1 つずつ日本語とたとえで説明**してから、どうするかを決めてもらう。

| 道具 | 見るもの | たとえ | 設定 |
|---|---|---|---|
| `terraform fmt`・`validate` | 書き方の形・文法 | 清書・文法の確認 | なし |
| TFLint（＋ AWS のルール 0.43.0） | 書き方のまちがい・説明（description）の書き忘れ・AWS で使えない値 | 設計図の校正係 | `infra/.tflint.hcl` |
| Trivy（config） | 設計図の安全の弱いところ（暗号化・公開・権限など） | 設計図の防犯診断 | 設計図の部品の上の `#trivy:ignore:...` |
| ShellCheck | `deploy/*.sh` の書き方の落とし穴 | 手順書の校正係 | スクリプトの `# shellcheck disable=...` |

コマンドは Bash ツール（Git Bash）で実行する。リポジトリの一番上は `/c/Users/pirishik/cursor/taskmanagement`。

## 0. 道具の場所を通す

道具は winget で入れてあるが、シェルの PATH には入っていない（WinGet/Links にリンクが作られないことがある）。毎回、先頭に足す。

```bash
W=/c/Users/pirishik/AppData/Local/Microsoft/WinGet/Packages
export PATH="$W/Hashicorp.Terraform_Microsoft.Winget.Source_8wekyb3d8bbwe:$W/TerraformLinters.tflint_Microsoft.Winget.Source_8wekyb3d8bbwe:$W/AquaSecurity.Trivy_Microsoft.Winget.Source_8wekyb3d8bbwe:$W/koalaman.shellcheck_Microsoft.Winget.Source_8wekyb3d8bbwe:$PATH"
for t in terraform tflint trivy shellcheck; do printf '%s: ' $t; command -v $t >/dev/null && echo OK || echo "なし"; done
```

「なし」と出たら、`winget install --id <ID> -e --source winget` で入れる（ID：`Hashicorp.Terraform`・`TerraformLinters.tflint`・`AquaSecurity.Trivy`・`koalaman.shellcheck`）。`--source winget` を付けないと、Microsoft Store のほうで Avast の証明書のエラー（0x8a15005e）になる。

版は `ci.yml` の Docker イメージとそろえる（terraform 1.16.5・tflint 0.64.0・trivy 0.75.0）。`ci.yml` の版を上げたら、手元も上げる。

## 1. Terraform の形と文法

```bash
cd /c/Users/pirishik/cursor/taskmanagement/infra
terraform fmt -check -recursive && echo "fmt OK"
terraform validate
```

- `fmt -check` で名前が出たファイルは、形がそろっていない。`terraform fmt` で直してよい（中身は変わらない）。
- `infra/` には台帳（`terraform.tfstate`）がある。`init` をやり直したり、`state` を触ったりしない。

## 2. TFLint

```bash
cd /c/Users/pirishik/cursor/taskmanagement/infra
tflint --init     # 初回と、.tflint.hcl の AWS のルールの版を変えたときだけ
tflint --format=compact
```

## 3. Trivy

リポジトリの一番上で、**家の IP の値（`terraform.tfvars`）があるときとないときの両方**で動かす。GitHub Actions には `terraform.tfvars` がない（Git に入れない）ので、ないときの結果が CI と同じになる。

⚠️ 先に、`infra/` に **plan の控え**（`tfplan`・`*.tfplan`。`terraform plan -out=` で作るファイル）がないことを確かめる。Trivy は控えも読み、設計図ではなく**控えの中の古い形**を調べてしまう（結果の表の Type が `terraformplan-snapshot` になる）。控えの中には設計図の `#trivy:ignore` が届かないので、目印が効かないように見える（2026-10-08 に、10/7 の古い控えのせいで AWS-0178 の目印が効かないとまちがえた）。plan の控えは、最初から scratchpad に書く。

```bash
cd /c/Users/pirishik/cursor/taskmanagement
ls infra/tfplan infra/*.tfplan 2>/dev/null && echo "⚠️ plan の控えがある。ユーザーに消してよいか聞く"
trivy config --quiet --exit-code 1 infra
trivy config --quiet --exit-code 1 --tf-vars infra/terraform.tfvars infra
```

結果の表の Type が、どのファイルも `terraform` になっていることを確かめる。

## 4. ShellCheck

```bash
cd /c/Users/pirishik/cursor/taskmanagement
shellcheck deploy/*.sh
```

## 5. 指摘を 1 つずつ決める

指摘があったら、すぐに直さない。**1 つずつ**、次の表にしてユーザーに見せる。

| 道具 | 指摘（番号） | 何が問題か（たとえ） | おすすめ | 理由 |
|---|---|---|---|---|

おすすめは、次のどちらか。

- **直す**：直しても、決めたこと（イシュー・要件定義書 No.25）が変わらないもの。書き方の落とし穴、説明の書き忘れなど
- **わざと（理由を書いて止める）**：練習のため・お金のために、ユーザーと決めてそうしているもの。どのイシューで決めたかを理由に書く

ユーザーが選んでから、手を動かす。

### 止めるときの書き方（必ず理由を書く）

理由のない目印は、未来のユーザーが「わざとなのか、直し忘れなのか」を区別できなくなる。

| 道具 | 書き方 | 場所 |
|---|---|---|
| Trivy | `# 理由（イシュー #番号）` の下に `#trivy:ignore:AWS-0000` | 指摘された部品（`resource`）のすぐ上 |
| ShellCheck | `# 理由` の下に `# shellcheck disable=SC0000` | 指摘された行のすぐ上 |
| TFLint | `# 理由` の下に `# tflint-ignore: ルール名` | 指摘された部品のすぐ上 |

- 目印を書いたら、**必ずもう一度その道具を動かして、本当に止まったかを確かめる**。効かないときは、書き方を変える前に、手順 3 の「plan の控え」を疑う。
- Trivy の番号は `AWS-0104` の形（古い `AVD-AWS-` の形ではない）。

### ⛔ 止めてはいけないもの

`aws-terraform-guide/安全チェック表.md` の「危険のサイン」にあたる指摘は、**わざとで止めない**。直すか、ユーザーにはっきり聞く。

- 入口（ingress）の `0.0.0.0/0`・`::/0`（出口ならふつう）
- RDS の `publicly_accessible = true`
- IAM の `"Action": "*"`・`"Resource": "*"`（何でも・どれでも）
- ポート 22 を開けるルール（入り方は Session Manager の通路。イシュー #61）

## 6. 直したあと

1. 1〜4 をもう一度全部動かし、**指摘 0 件**を確かめる。
2. AWS にログインしていれば（`aws sts get-caller-identity --profile dothething` の末尾が `54589408`）、`infra` で `terraform plan -detailed-exitcode` を動かし、終わりのコードが **0（変更なし）** を確かめる。説明・コメント・目印だけの直しで、AWS の実物が変わる plan が出たら、何かを変えてしまっている。plan は読むだけで、apply はしない。
3. GitHub Actions と同じ動きを確かめたいときは、**Git に入るファイルだけを scratchpad にコピーして**、`ci.yml` と同じ Docker イメージで動かす。`infra/` をそのまま Docker に渡さない（台帳や `.terraform` を書き換えてしまうため）。

   ```bash
   S=<scratchpad>/ci-test; mkdir -p "$S"
   cd /c/Users/pirishik/cursor/taskmanagement && git ls-files -co --exclude-standard infra deploy | tar -cf - -T - | tar -xf - -C "$S"
   cd "$S" && export MSYS_NO_PATHCONV=1 && W="$(pwd -W)"
   # 以下、ci.yml の infra ジョブの docker run を、$PWD を $W に読みかえて動かす
   ```

## 7. 結果を伝える

最後に、次の表で伝える。

| 道具 | 結果 |
|---|---|
| terraform fmt・validate | ✅ OK |
| TFLint | ✅ 指摘 0 件 |
| Trivy（tfvars あり・なし） | ✅ 指摘 0 件（わざと：〇件、理由は設計図に） |
| ShellCheck | ✅ 指摘 0 件 |

直したファイルがあれば、Git の流れ（`CLAUDE.md`：イシュー → ブランチ → PR）に乗せる。PR では `infra` を含む 3 つの必須のチェックが成功するのを確かめる。
