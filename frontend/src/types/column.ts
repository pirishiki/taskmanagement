// API（GET /api/columns）から返ってくる列1つの形
// バックエンドの BoardColumnResponse.java に対応する
// fixed：「基本の列」（やるべきこと・進行中・終わったこと）の印。基本の列は消せない
// done：「完了の列」の印。全部の列の中で1つだけ true（終わったこと）。完了ボタン（○）を押したタスクは、この列へ移る
export type BoardColumn = {
  id: number
  name: string
  sortOrder: number
  fixed: boolean
  done: boolean
  // 版の番号（docs/同時編集の設計書.md の 3 節）。列の名前・並び順が変わるたびに 1 上がる
  // 列を消すときに一緒に送る（ほかの画面で名前を変えたばかりの列を、古い名前の確認のまま消さないため）
  version: number
}
