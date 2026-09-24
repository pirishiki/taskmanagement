# フロントエンド（React + TypeScript + Vite + Tailwind CSS）

タスクをボード画面（やるべきこと / 進行中 / 終わったこと の3列）に表示し、タスク名のキーワードで検索したり、各列の下のフォームからタスクを追加したりする画面。

## 必要なもの

- Node.js（LTS 版。npm も一緒に入る）

## 起動手順

DB とバックエンドを先に起動しておく（手順は [backend/構成表.md](../backend/構成表.md) を参照）。

| 順番 | 実行場所 | コマンド | 説明 |
| --- | --- | --- | --- |
| 1 | `frontend`フォルダ | `npm install` | 部品（ライブラリ）を取り寄せる（初回と、package.json が変わったときだけ） |
| 2 | `frontend`フォルダ | `npm run dev` | 開発用サーバーを起動する |
| 3 | ブラウザ | http://localhost:5173 を開く | ボード画面が表示される |

## API との通信

画面は `/api/tasks` を呼ぶ。開発用サーバー（Vite）のプロキシが、それをバックエンド（`http://localhost:8080`）に取り次ぐ（[vite.config.ts](vite.config.ts) の `server.proxy`）。
ブラウザから見ると同じアドレス（5173）とのやりとりになるので、バックエンド側で CORS の許可を設定する必要はない。

## ファイル構成

| ファイル | 役割 |
| --- | --- |
| `src/main.tsx` | 入口。`index.html` の `#root` に `App` を表示する |
| `src/App.tsx` | 画面全体。タスク・読み込み中・エラーの状態を持ち、検索と追加を実行する |
| `src/types/task.ts` | API から返ってくるタスクの型（バックエンドの `Task.java` に対応）と、登録時に送る `NewTask` 型 |
| `src/api/taskApi.ts` | API を呼ぶ関数（`fetchTasks`：一覧の取得、`createTask`：POST で登録） |
| `src/components/AddTaskForm.tsx` | タスク追加フォーム（タスク名・優先度（既定は中）・期限日。タスク名が空欄なら何もしない） |
| `src/components/SearchBar.tsx` | 検索ボックス（検索ボタンか Enter キーで検索） |
| `src/components/Board.tsx` | タスクを status ごとに3列に分ける |
| `src/components/Column.tsx` | 1列分（見出し・カードのリスト・追加フォーム） |
| `src/components/TaskCard.tsx` | カード1枚（タスク名・優先度・期限） |

## その他のコマンド

| コマンド | 説明 |
| --- | --- |
| `npm run build` | 型チェックをしてから、公開用のファイルを `dist/` に作る |
| `npm run lint` | コードの書き方をチェックする（oxlint） |
