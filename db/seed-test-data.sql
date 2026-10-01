-- 開発用のテストデータ
-- 実行方法（taskmanagement フォルダで）:
--   docker compose exec -T postgres psql -U postgres -d taskdb < db/seed-test-data.sql
-- 注意: 列（board_columns）とタスク（tasks）の中身をすべて消してから入れ直す（何度流しても同じ状態になる）
-- 前提: バックエンドを一度起動してテーブルが作られていること（テーブルは起動時に Flyway が db/migration の SQL で作る）

SET client_encoding = 'UTF8';

-- RESTART IDENTITY：番号の振り直し。列は 1・2・3 番から振られるので、下のタスクの column_id にその番号を書ける
TRUNCATE tasks, board_columns RESTART IDENTITY;

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
