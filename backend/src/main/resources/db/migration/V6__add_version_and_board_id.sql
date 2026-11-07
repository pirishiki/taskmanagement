-- V6：同時編集の直し（イシュー #71。docs/同時編集の設計書.md の 3.1 節・5 節）
-- ・タスクと列に「版の番号」（version）を足す。中身が変わるたびに 1 上がる（楽観的ロック）
--   画面は「見ていた版」を一緒に送り、今の版とちがえば、サーバーは何も書かずに 409 を返す
--   （開いたままの古い画面が、ほかの画面での変更を黙って上書きしないようにするため）
-- ・DB ごとに 1 つの「ボードの番号」（board_meta の board_id）を作る
--   DB を作り直すと、カードの番号（id）も版も 1・0 から振り直されるので、版だけでは「別の DB のカード」だと分からない
--   そこで、DB を作り直すたびに変わる番号を持ち、画面が覚えた番号とちがえば書き込みを止める
-- （V1〜V5 はもう DB に当てたので書き換えず、このファイルを足す）

-- 版の番号。今ある行は 0 から始まる。DEFAULT 0 は残す（テストデータの投入のように、SQL で直接入れる行も 0 になる）
-- DEFAULT つきの列の追加なので、PostgreSQL 11 より新しい版では、表を書き直さずにすぐ終わる
ALTER TABLE tasks ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE board_columns ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ボードの番号。いつも 1 行だけ（id は 1 しか入れられない）
CREATE TABLE board_meta (
    id       SMALLINT PRIMARY KEY CHECK (id = 1),
    -- DB を作るたびに新しくなる番号（UUID）。gen_random_uuid() は PostgreSQL 13 からの標準の関数
    board_id UUID     NOT NULL
);

INSERT INTO board_meta (id, board_id) VALUES (1, gen_random_uuid());
