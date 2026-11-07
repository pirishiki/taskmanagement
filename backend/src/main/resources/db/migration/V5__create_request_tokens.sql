-- V5：番号札（Idempotency-Key）を記録する表を作る（イシュー #69。docs/二重送信の設計書.md の 2 節）
-- 画面は、タスク・列を追加するたびに番号札（UUID）を作り、ヘッダー Idempotency-Key で送る
-- 返事が届かずに押し直しても、同じ札なら新しく作らず、1 回目と同じ結果を返す（2 件にならないようにするため）
-- 札は、追加と同じトランザクションで記録する（追加だけ残って札がない・札だけ残って追加がない、にならないように）
-- （V1〜V4 はもう DB に当てたので書き換えず、このファイルを足す）

CREATE TABLE request_tokens (
    -- 番号札（画面が作る UUID）。同じ札が 2 回来たら、主キーが重なることで気づける
    token       UUID        PRIMARY KEY,
    -- 何を追加した札か（task か column）。ちがう種類の追加に同じ札を使い回したら、ちがう中身として 409 にする
    kind        VARCHAR(16) NOT NULL,
    -- 作ったタスク・列の番号。札を入れてから作り終わるまでの途中だけ NULL（同じトランザクションの中なので、ほかからは見えない）
    -- 外部キーにはしない：作ったものがあとで消されても札は残し、同じ札が来たら 404（作り直さない）と答えるため
    resource_id BIGINT,
    -- 中身の指紋（SHA-256 を 16 進にした 64 文字）。同じ札で、ちがう中身が来たら 409 にする
    fingerprint CHAR(64)    NOT NULL,
    -- 札を記録した日時。24 時間をこえた札は、追加のたびに消す
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- kind は決まった 2 つの値だけ（アプリの書きまちがいで、知らない種類が入らないように）
    CONSTRAINT ck_request_tokens_kind CHECK (kind IN ('task', 'column'))
);

-- 古い札の掃除（created_at が 24 時間より前の行を消す）を、表全体を読まずに速くする
CREATE INDEX idx_request_tokens_created_at ON request_tokens (created_at);
