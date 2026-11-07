-- 開発用のテストデータ
-- 実行方法（taskmanagement フォルダで）:
--   docker compose exec -T postgres psql -U postgres -d taskdb < db/seed-test-data.sql
-- 注意: 列（board_columns）とタスク（tasks）と、二重送信を防ぐ番号札（request_tokens）の中身をすべて消してから入れ直す（何度流しても同じ状態になる）
-- 番号札も消す理由：番号を振り直すので、古い札が別のタスク・列を指してしまうのを防ぐため（イシュー #69）
-- ボードの番号（board_meta の board_id）も新しくする理由：番号を振り直すので、流す前から開いたままの古い画面が、
-- 番号（id）が同じ「別のカード」を書き換えないようにするため。古い画面の書き込みは 409（BOARD_CHANGED）で止まり、
-- 画面に黄色の帯「読み込み直す」が出る（イシュー #71。docs/同時編集の設計書.md の 5 節）
-- 前提: バックエンドを一度起動してテーブルが作られていること（テーブルは起動時に Flyway が db/migration の SQL で作る）

-- エラーが 1 つでも出たら、そこで止める（続けると、残りの文がエラーを重ねるだけで分かりにくい。トランザクションは取り消される）
\set ON_ERROR_STOP on

SET client_encoding = 'UTF8';

-- 全部を 1 つのトランザクションにする（途中の、空の表やボードの番号が古いままの状態を、アプリに見せないため）
BEGIN;

-- アプリの書き込みと同じ鍵（番号 69001。WriteLockTransactionManager の LOCK_KEY）をとる
-- 書き込みの最中なら終わるのを待ち、投入の最中に来た書き込みは、投入が終わるまで待たせる
-- 待たされた書き込みは、鍵をとったあとにボードの番号を比べ直すので、新しいカードを書き換えずに 409 で止まる（BoardIdGuard）
SELECT pg_advisory_xact_lock(69001);

-- 書き出し（export）は鍵をとらずに board_columns → tasks の順に読む（REPEATABLE_READ）。TRUNCATE は tasks から先に
-- 表をつかむので、書き出しの最中に流すと、互いに相手の表を待つデッドロック（40P01）になりうる
-- そこで、書き出しが読むのと同じ順（board_columns → tasks）で、先に 3 つの表をまとめてつかむ
-- （書き出しの最中なら、終わるのを待ってからつかむ。つかんだあとに来た書き出しは、投入が終わるのを待つ）
LOCK TABLE board_columns, tasks, request_tokens IN ACCESS EXCLUSIVE MODE;

-- RESTART IDENTITY：番号の振り直し。列は 1・2・3 番から振られるので、下のタスクの column_id にその番号を書ける
-- 入れる行の版（version）は、列の既定値の 0 になる
TRUNCATE tasks, board_columns, request_tokens RESTART IDENTITY;

-- ボードの番号を新しくする（gen_random_uuid() は PostgreSQL 13 からの標準の関数）
UPDATE board_meta SET board_id = gen_random_uuid();

INSERT INTO board_columns (name, sort_order, is_fixed, is_done) VALUES
    ('やるべきこと', 0, TRUE, FALSE),  -- 1 番（基本の列）
    ('進行中',       1, TRUE, FALSE),  -- 2 番（基本の列）
    ('終わったこと', 2, TRUE, TRUE);   -- 3 番（基本の列・完了の列）

INSERT INTO tasks (text, column_id, priority, due_date, sort_order) VALUES
    ('スーパーで買い物をする',       1, 'high',   '2026-09-25', 0),
    ('週末の買い物リストを作る',     1, 'low',    NULL,         1),
    ('レポートの下書きを書く',       1, 'medium', '2026-10-01', 2),
    ('Review pull request',          1, 'high',   '2026-09-24', 3),
    ('歯医者の予約をする',           2, 'medium', '2026-09-30', 0),
    ('React の勉強をする',           2, 'high',   NULL,         1),
    ('code review のコメントに返信', 2, 'low',    '2026-10-05', 2),
    ('部屋の掃除をする',             3, 'low',    NULL,         0),
    ('Docker をインストールする',    3, 'medium', '2026-09-20', 1),
    ('要件定義書を書く',             3, 'high',   '2026-09-15', 2);

-- ここで初めて、ほかから新しい状態が見える。鍵もここで外れる
COMMIT;
