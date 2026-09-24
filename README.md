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
| ドラッグ＆ドロップ | dnd-kit | 未導入（ドラッグ機能の実装時に導入） |
| スタイリング | Tailwind CSS | 4.3.3 |
| 静的解析 | oxlint | 1.81 |

### バックエンド（`backend/`）

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| 言語 | Java | 21（Eclipse Temurin 21.0.12.1、LTS） |
| フレームワーク | Spring Boot | 4.1.1 |
| ビルドツール | Gradle | 9.7.1（`gradlew`ラッパー経由） |
| API形式 | REST API | - |

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
| RDBMS | PostgreSQL（Dockerコンテナ、ポート5433） | 17.11 |

### 開発ツール

| 区分 | 技術 | バージョン |
| --- | --- | --- |
| バージョン管理 | Git | 2.55.0 |
| リポジトリホスティング | GitHub | - |

## 起動方法

- DB・バックエンド：[backend/構成表.md](backend/構成表.md) の「起動手順」
- フロントエンド：[frontend/README.md](frontend/README.md) の「起動手順」
