-- 開発用のテストデータ
-- 実行方法（taskmanagement フォルダで）:
--   docker compose exec -T postgres psql -U postgres -d taskdb < db/seed-test-data.sql
-- 注意: tasks テーブルの中身をすべて消してから入れ直す（何度流しても同じ状態になる）
-- 前提: バックエンドを一度起動して tasks テーブルが作られていること

SET client_encoding = 'UTF8';

TRUNCATE tasks RESTART IDENTITY;

INSERT INTO tasks (text, status, priority, due_date, sort_order) VALUES
    ('スーパーで買い物をする',       'todo',  'high',   '2026-09-25', 0),
    ('週末の買い物リストを作る',     'todo',  'low',    NULL,         1),
    ('レポートの下書きを書く',       'todo',  'medium', '2026-10-01', 2),
    ('Review pull request',          'todo',  'high',   '2026-09-24', 3),
    ('歯医者の予約をする',           'doing', 'medium', '2026-09-30', 0),
    ('React の勉強をする',           'doing', 'high',   NULL,         1),
    ('code review のコメントに返信', 'doing', 'low',    '2026-10-05', 2),
    ('部屋の掃除をする',             'done',  'low',    NULL,         0),
    ('Docker をインストールする',    'done',  'medium', '2026-09-20', 1),
    ('要件定義書を書く',             'done',  'high',   '2026-09-15', 2);
