// ボードの番号（ヘッダー Board-Id）の係（docs/同時編集の設計書.md の 5 節・6.1 節）
// DB が作り直される（RDS の作り直し・テストデータの投入）と、カードの番号（id）が 1 から振り直される
// 開いたままの古い画面が、番号が同じ「別のカード」を書き換えないように、DB ごとの番号を覚えて、書き込みのたびに送る
//
// たとえ：ホワイトボードの裏の製造番号。最初に見たボードの番号を覚えておき、書き込むときに「このボードのつもりです」と伝える
// ボードが取り替えられていれば、サーバーは書き込まずに 409（BOARD_CHANGED）で止める

export const BOARD_ID_HEADER = 'Board-Id'

// 画面が最初に受け取ったボードの番号（まだ何も受け取っていなければ null）
// 一度覚えたら、ちがう番号が届いても書き換えない（書き換えると、古い画面のまま新しい DB に書き込めてしまうため）
let knownBoardId: string | null = null

// ボードが変わったと分かったか。一度 true になったら、ページを読み込み直すまで戻らない（黄色の帯は消えない）
let changed = false

const listeners = new Set<() => void>()

// 書き込みに付けるヘッダー。まだ番号を知らなければ付けない（サーバーは、付いていなければ確かめない）
export function boardIdHeaders(): Record<string, string> {
  return knownBoardId === null ? {} : { [BOARD_ID_HEADER]: knownBoardId }
}

// 返事（成功・失敗とも）のヘッダーを見る。最初の番号なら覚え、覚えた番号とちがえば「変わった」と知らせる
// 返事に番号がない（バックエンドまで届かなかった、など）ときは何もしない
export function observeBoardId(response: Response): void {
  const boardId = response.headers.get(BOARD_ID_HEADER)
  if (boardId === null || boardId === '') {
    return
  }
  if (knownBoardId === null) {
    knownBoardId = boardId
  } else if (boardId !== knownBoardId) {
    markBoardChanged()
  }
}

// ボードが変わったことを、見張っている画面（App の黄色の帯）に知らせる。2 回目からは知らせない
export function markBoardChanged(): void {
  if (changed) {
    return
  }
  changed = true
  listeners.forEach((listener) => listener())
}

// ボードが変わったときに呼んでほしい関数を登録する。すでに変わっていれば、すぐに呼ぶ
// 返す関数を呼ぶと、登録をやめる（React の useEffect の後片付けで使う）
export function onBoardChanged(listener: () => void): () => void {
  listeners.add(listener)
  if (changed) {
    listener()
  }
  return () => {
    listeners.delete(listener)
  }
}
