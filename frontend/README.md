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
| `src/App.tsx` | 画面全体。タスク・読み込み中・エラー・検索キーワードの状態を持ち、検索・追加・編集（PUT）・完了（PATCH）・削除（DELETE）・並び替え（ドラッグ、および優先度順・期限が近い順の自動並び替え）を実行する。並び替えは先に画面を書き換えてから保存し、失敗したら DB の状態を取り直す。削除はサーバーで消せてから画面の一覧から外す |
| `src/types/task.ts` | API から返ってくるタスクの型（バックエンドの `Task.java` に対応）、登録・編集（PUT）時に送る `NewTask` 型、一部だけ更新（PATCH）するときに送る `TaskPatch` 型、列の並び替えの基準（優先度順・期限が近い順）を表す `SortCriterion` 型 |
| `src/api/taskApi.ts` | API を呼ぶ関数（`fetchTasks`：一覧の取得、`createTask`：POST で登録、`updateTask`：PUT で丸ごと更新、`patchTask`：PATCH で一部だけ更新、`deleteTask`：DELETE で削除、`reorderTasks`：列の並び順をまとめて更新） |
| `src/components/AddTaskForm.tsx` | タスク追加フォーム。ふだんは「＋ カードを追加」だけを出し、押すと開く（タスク名・優先度（既定は中）・期限日。タスク名が空欄なら何もしない。追加後も開いたまま。✕ か Esc キーで閉じる） |
| `src/components/SearchBar.tsx` | 検索ボックス（検索ボタンか Enter キーで検索。検索中は「✕ 検索を解除」を出す） |
| `src/components/Board.tsx` | タスクを status ごとに3列に分け、列の中を sortOrder 順に並べる。ドラッグ＆ドロップ全体（dnd-kit の `DndContext`）を受け持ち、ドラッグ中は仮の一覧で着地点を見せる。検索中はドラッグできないことを表示する |
| `src/components/Column.tsx` | 1列分（見出し・並び替えセレクト・カードのリスト・追加フォーム）。並び替えセレクトで優先度順・期限が近い順を選ぶと、その列を一度だけ並べ直す（検索中は使えない）。列の中で並び替えられるようにし、空の列にもカードを落とせるようにする |
| `src/components/TaskCard.tsx` | カード1枚（タスク名・優先度・期限）。○ で完了、✎ でクイック編集（画面を暗くし、横に優先度・期限のメニュー）、× で削除（確認のダイアログを出し、OK のときだけ削除する）。ドラッグでつかめる。ドラッグ中にマウスに付いてくる分身（`TaskCardOverlay`）もここにある |

## その他のコマンド

| コマンド | 説明 |
| --- | --- |
| `npm run build` | 型チェックをしてから、公開用のファイルを `dist/` に作る |
| `npm run lint` | コードの書き方をチェックする（oxlint） |
