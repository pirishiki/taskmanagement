-- V4：基本の3列（やるべきこと・進行中・終わったこと）を消せないようにする（イシュー #38）
-- 要件定義書 No.13 は「基本の3列『以外の』列を自由に作れる」こと。基本の3列は、カンバンの基本の流れ（未着手→作業中→完了）なので残す
-- 列に「基本の列」の印（is_fixed）を足し、V3 で入れた3列に付ける。あとから足した列には付けない（FALSE）
-- （V3 はもう DB に当てたので書き換えず、このファイルを足す）

ALTER TABLE board_columns ADD COLUMN is_fixed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE board_columns
SET is_fixed = TRUE
WHERE name IN ('やるべきこと', '進行中', '終わったこと');
