---
name: infra-check
description: taskmanagement の AWS の設計図（infra/）とデプロイの手順（deploy/）の品質チェック（terraform fmt・validate、TFLint、Trivy、ShellCheck）。中身は共通のスキル ~/.claude/skills/infra-check/SKILL.md にあり、ここはそれを指すだけ。「品質チェックをして」「Terraform をチェックして」「tflint」「trivy」「shellcheck」「infra のチェックが落ちた」「PR を出す前に確かめたい」など、infra/ や deploy/ を変えたとき、または CI の infra が失敗したときは、必ずこのスキルを使うこと。
---

# AWS の設計図とデプロイの手順の品質チェック（infra-check）

このスキルの中身は、taskmanagement と SNSliketwitter で共通の **`~/.claude/skills/infra-check/SKILL.md`**（`C:\Users\pirishik\.claude\skills\infra-check\SKILL.md`）にある（イシュー #65 でまとめた。中身が 2 か所あると、片方だけ直して食いちがうため）。

1. そのファイルを読む。
2. 手順 0 の「どちらのプロジェクトを調べるか」は **taskmanagement**（`R=/c/Users/pirishik/cursor/taskmanagement`）にして、あとは書いてあるとおりに進める。

そのファイルがないとき（別の PC など）は、勝手にチェックを「OK」とせず、ユーザーに伝える。この PR より前の中身は、Git の履歴（イシュー #63 の PR）にある。
