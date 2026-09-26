# Task Management
Task Management Project
要件を定義をする際は小さく段階的に進める
AIが先に進めようとする場合も止めて計画を立てることのほうが重要
顧客の求めるものを汲んでモノを作ろうとするがもともとシステム的に意味がない場合もある
Javaとspringbootを使えると企業が採用しやすい。データにもあらわれている

## 技術スタック

詳しい要件は [要件定義書.md](要件定義書.md) を参照。

### フロントエンド（`frontend/`）

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| 実行環境 | Node.js | 24.19.0（LTS） |
| フレームワーク | React | 19.3.0 |
| 言語 | TypeScript | 6.0.3 |
| ビルドツール | Vite | 8.3.0 |
| パッケージ管理 | npm | 11.17.0 |
| ドラッグ＆ドロップ | dnd-kit（@dnd-kit/core・@dnd-kit/sortable） | 6.3.1・10.0.0 |
| スタイリング | Tailwind CSS | 4.3.3 |
| 静的解析 | oxlint | 1.85 |

### バックエンド（`backend/`）

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| 言語 | Java | 21（Eclipse Temurin 21.0.12.1、LTS） |
| フレームワーク | Spring Boot | 4.1.1 |
| ビルドツール | Gradle | 9.7.1（`gradlew`ラッパー経由） |
| API形式 | REST API | - |
| 静的解析 | PMD（Gradle の `pmd` プラグイン、規則は quickstart） | 7.24.0 |

主要な部品（ライブラリ）。Spring Boot 4.1.1 が組み合わせを決めて自動で取り寄せているもの。

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| Web（REST API） | Spring Web MVC | 7.0.9 |
| Webサーバー（組み込み） | Apache Tomcat | 11.0.24 |
| JSON変換 | Jackson | 3.1.5 |
| DBアクセス | Spring Data JPA | 4.1.1 |
| DBアクセス（ORM） | Hibernate ORM | 7.4.5 |
| DB接続プール | HikariCP | 7.0.2 |
| DBドライバ | PostgreSQL JDBC Driver | 42.7.13 |
| 入力チェック | Hibernate Validator | 9.1.3 |

### データベース

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| RDBMS | PostgreSQL（Dockerコンテナ、ポート5432） | 17.11 |

### 開発ツール

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| バージョン管理 | Git | 2.55.0 |
| リポジトリホスティング | GitHub | - |
| 自動チェック（CI） | GitHub Actions（`.github/workflows/ci.yml`。PR と master へのプッシュで、フロントの lint・build と、バックエンドの PMD・コンパイルを動かす） | - |

## 起動方法

- DB・バックエンド：[backend/構成表.md](backend/構成表.md) の「起動手順」
- フロントエンド：[frontend/README.md](frontend/README.md) の「起動手順」

## 品質チェックで見つかった問題（2026-09-27）

要件定義書・各種ドキュメントを踏まえて、React／Spring Boot の標準から外れる所や、良くない実装を洗い出した（イシュー #17）。
「こんな問題があった」という記録として残し、あとで同じ間違いをしないための振り返りに使う。

重さの目安：**高**＝データがおかしくなる・500 エラーになる／**中**＝標準のやり方から外れる・条件によっては困る／**低**＝小さな改善

### この PR で直したもの

| # | 重さ | 場所 | どんな問題だったか | 起きていたこと | 直し方 |
|---|---|---|---|---|---|
| 1 | 高 | `ReorderRequest.java`、`TaskController.reorder` | 並び替え API に入力チェック（`@Valid`）がなかった | `"status": "abc"` がそのまま DB に入り、どの列にも出ない行方不明のタスクになる。`orderedIds` を省くと 500 | `@NotNull`・`@Pattern` を付け、Controller に `@Valid` を付けた（400 を返す） |
| 2 | 高 | `TaskRequest.java`、`TaskPatchRequest.java`、画面の入力欄 | タスク名の長さに上限がなかった | DB の列は `varchar(255)` なので、256 文字以上だと DB への保存で失敗し 500 | `@Size(max = 255)`（400 を返す）と、画面の `maxLength={255}` |
| 3 | 高 | `TaskService.java` | `@Transactional` がなかった | 並び替えの途中で失敗すると、一部のカードだけ番号が書き換わった状態が残りうる | クラスに `@Transactional`、読み取りに `readOnly = true` |
| 4 | 中 | `TaskController.java` | 削除だけ Controller が Repository を直接呼んでいた | Service の `@Transactional` などの決まりが、削除にだけ効かない | Service に `deleteTask` を作り、Controller は Service だけを使う |
| 7 | 中 | `application.properties` | 不要な設定（dialect・driverClassName）があり、open-in-view が未設定 | 起動するたびに WARN が2件出ていた | 不要な設定を消し、`spring.jpa.open-in-view=false` を明示（WARN 0 件） |
| 8 | 中 | `application.properties` | DB のパスワードをファイルに直書きしていた | 本番で使うと、公開されるファイルにパスワードが載る | `${DB_PASSWORD:postgres}`（環境変数があればそれを使う） |
| 12 | 中 | `App.tsx` | 検索の結果が、頼んだ順に届く前提になっていた | 検索を続けて押すと、先に頼んだ古い結果があとから届いて、新しい結果を上書きしうる | 取得ごとに番号を付け、最後に頼んだものの結果だけを使う |
| 13 | 中 | `TaskCard.tsx` | カード全体に dnd-kit のキーボード操作が付いていた | ○・✎・× にフォーカスして Enter／Space を押すと、ボタンが押されずドラッグが始まった（**ブラウザで起きることを確認済み**） | ボタンで押したキーを `stopPropagation()` でカードに伝えない |
| 14 | 低 | `TaskService.java`、`TaskRepository.java` | 新しいカードの番号を決めるのに列の全件を読み、並び替えは二重ループだった | 列のカードが増えるほど、むだな読み込みと計算が増える | 一番下の1件だけを取る（`findTopByStatusOrderBySortOrderDesc`）。並び替えは ID → タスクの Map |
| 17 | 低 | `AddTaskForm.tsx`、`Column.tsx`、`Board.tsx` | セレクトの値を `as` で決めつけていた | 選択肢を書き間違えても、TypeScript が気づけない | 選択肢の一覧から探して型を決める。dnd-kit の id の `as` は、理由をコメントに書いて残した |
| 18 | 低 | `backend/DB設計書.md` | 並び順を計算するのを「TaskController」と書いていた。入力チェックの表が足りなかった | ドキュメントがコードと食い違っていた | TaskService に訂正し、API ごとの入力チェックを全部書いた |
| 19 | 低 | `build.gradle`、`BackendApplicationTests.java` | 字下げがタブとスペースで混ざっていた | ファイルによって見た目がそろわない | 4スペースにそろえた |

あわせて、同じことが起きにくいように仕組みを入れた。

- oxlint に React 定番の規則（effect の依存の書き漏れ）を足し、警告でも失敗にした（`App.tsx` の書き漏れを1件見つけて直した）
- バックエンドに PMD を入れた（空のメソッドの理由の書き漏れなど3件を見つけ、2件を直し、的外れな1件は理由を書いて止めた）
- GitHub Actions で、PR ごとにこれらのチェックが自動で動くようにした

### あとの PR で直したもの

| # | 重さ | 場所 | どんな問題だったか | 直し方 | イシュー |
|---|---|---|---|---|---|
| 5 | 中 | `TaskController.java` | DB の形（エンティティ `Task`）をそのまま API の返事にしていた。DB の形を変えると、画面への返事の形も勝手に変わってしまう | 返事専用の型（DTO）`TaskResponse` を作り、Controller はそれに詰め替えて返す。返事の中身は変わらないことを、変更の前後で比べて確かめた | #21 |
| 6 | 中 | `Task.java`、各 Request | status・priority を文字列＋正規表現で持っていた。Java のコードの中では `"tood"` のような書き間違いも作れてしまう | enum（`TaskStatus`・`TaskPriority`）にした。JSON と DB では今までどおり小文字（`@JsonValue`・`@JsonCreator` と AttributeConverter） | #21 |

### 今後の候補（別の PR で直す）

それぞれ新しい考え方を1つ覚える必要があるので、別の PR にした。[要件定義書.md](要件定義書.md) の 5.2 に記録してある。

| # | 重さ | 場所 | どんな問題か | 標準のやり方 | 要件定義書 |
|---|---|---|---|---|---|
| 9 | 中 | `application.properties` | 起動のたびにテーブルを自動で直している（`ddl-auto=update`） | Flyway などでテーブルの変更を記録する | No.20 |
| 10 | 低 | バックエンド全体 | エラーの返事の形がばらばら。エラーを1か所で受け止める仕組み（グローバル例外ハンドラー）がない | `@RestControllerAdvice` のグローバル例外ハンドラーで、Spring 標準の ProblemDetail の形にそろえて返す | No.21（#16 と同じ PR） |
| 11 | 中 | `BackendApplicationTests.java` | テストが起動中の Docker の DB に依存している | Testcontainers（使い捨ての DB） | No.17 |
| 15 | 低 | `TaskCard.tsx` | 編集画面を開いた時点の位置に固定していて、スクロールでずれる | React の createPortal | No.22 |
| 16 | 低 | `App.tsx` | エラーメッセージがいつも「バックエンドが起動しているか確認」 | サーバーの返事（400・404・500）ごとに変える | No.21 |
| （19 の再発防止） | 低 | バックエンド全体 | 字下げなどの「書き方の決まり」を自動でチェックしていない（#19 は目で見て見つけた。PMD では見つけられない） | Checkstyle | No.23（No.17 と同じ PR） |

※ 読み上げソフトへの対応（jsx-a11y の指摘）と、削除の確認に `window.confirm` を使うことは、このアプリの方針として決めているので、問題として扱っていない。
