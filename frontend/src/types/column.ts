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
}
