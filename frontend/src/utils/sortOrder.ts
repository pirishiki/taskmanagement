// ドラッグのあとの確かめ（docs/同時編集の設計書.md の 6.1 節）に使う
// サーバーが返した番号（sortOrder）が、画面で落とした位置のすぐ上・すぐ下のカード（あるほう）の番号の間に入っているか
// 入っていなければ、画面の番号が古くなっている（サーバーが列の番号を振り直した、ほかの画面で並べ替えられた、など）ので、
// 画面の並びがサーバーとずれて見える。そのときは、一覧を取り直す
export function fitsBetween(sortOrder: number, above: { sortOrder: number } | null, below: { sortOrder: number } | null): boolean {
  return (above === null || above.sortOrder < sortOrder) && (below === null || sortOrder < below.sortOrder)
}
