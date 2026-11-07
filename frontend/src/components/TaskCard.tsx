import { useSortable } from '@dnd-kit/sortable'
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import type { Task, TaskContent, TaskPatch, UpdateResult } from '../types/task'

// 優先度ごとの表示名と色（試作版 style.css と同じ色）
const priorityLabels: Record<Task['priority'], string> = {
  high: '高',
  medium: '中',
  low: '低',
}

const priorityColors: Record<Task['priority'], string> = {
  high: 'bg-[#eb5a46]',
  medium: 'bg-[#ff9f1a]',
  low: 'bg-[#61bd4f]',
}

const priorities: Task['priority'][] = ['high', 'medium', 'low']

// カードの中のボタン（○・✎・×）で押したキーを、外側のカードに伝えない
// カード全体に dnd-kit の「キーボードでつかむ仕掛け」が付いているため、止めないと Enter や Space でボタンが押されず、ドラッグが始まってしまう
function stopKeyFromReachingCard(event: KeyboardEvent<HTMLButtonElement>) {
  event.stopPropagation()
}

type Props = {
  task: Task
  // 完了の列の番号。○ を押したタスクは、この列へ移る（列の一覧をまだ取れていないときは null）
  doneColumnId: number | null
  // 書き込み中（返事待ち）か。書き込み中は ○・✎・× を押せず、ドラッグでつかめず、少し薄く表示する（docs/同時編集の設計書.md の 6.1 節）
  busy: boolean
  // 編集の保存。version は、このカードが控えている版（✎ を開いたときの版。止められたあとは、いまの内容の版）
  onUpdate: (id: number, task: TaskContent, version: number) => Promise<UpdateResult>
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  // 編集フォームを開いた・閉じたことを伝える（Board が、編集中のカードを開いたときの列・位置に留める）
  onEditStart: (task: Task) => void
  onEditEnd: () => void
}

// カードの下段（優先度と期限）。ふだんのカードと編集中のカードの両方で使う
function CardLabels({ priority, dueDate }: { priority: Task['priority']; dueDate: string | null }) {
  return (
    <div className="mt-2 flex items-center gap-3 text-xs text-gray-500">
      <span className="flex items-center gap-1">
        <span className={`inline-block h-2.5 w-2.5 rounded-full ${priorityColors[priority]}`} />
        {priorityLabels[priority]}
      </span>
      {dueDate && <span>期限: {dueDate}</span>}
    </div>
  )
}

// ドラッグ中にマウスに付いてくる、カードの分身（見た目だけで、ボタンは動かない）
// ほんの少しだけ（反時計回りに0.5度）傾けて影を濃くし、持ち上げている感じを出す
export function TaskCardOverlay({ task }: { task: Task }) {
  return (
    <div className="-rotate-[0.5deg] cursor-grabbing rounded bg-white p-3 shadow-lg">
      <p className="text-sm whitespace-pre-wrap text-gray-800">{task.text}</p>
      <CardLabels priority={task.priority} dueDate={task.dueDate} />
    </div>
  )
}

function TaskCard({ task, doneColumnId, busy, onUpdate, onPatch, onDelete, onEditStart, onEditEnd }: Props) {
  // 編集中のカードを出す位置。null のあいだは編集していない
  // 編集中のカードに目が向くように、画面を暗くして、その上の同じ位置に編集用のカードを重ねる
  const [editPosition, setEditPosition] = useState<DOMRect | null>(null)
  const [text, setText] = useState(task.text)
  const [priority, setPriority] = useState<Task['priority']>(task.priority)
  const [dueDate, setDueDate] = useState(task.dueDate ?? '')
  // 保存のときに送る版（✎ を開いたときの版の控え。docs/同時編集の設計書.md の 6.2 節）
  // 開いたあとに、後ろのカードが新しい内容に入れ替わっても、利用者が見ていたのは開いたときの内容なので、その版で送る
  // （新しい版で送ると、利用者が見ていない変更を、黙って上書きしてしまう）
  const [baseVersion, setBaseVersion] = useState(task.version)
  // 保存がほかの画面での変更で止められたときの「いまの内容」。null なら止められていない
  const [conflict, setConflict] = useState<Task | null>(null)
  const cardRef = useRef<HTMLDivElement>(null)
  // 保存の送信中。state は表示用、ref は素早い 2 回目を防ぐ用（state は描き直しまで古いため）
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)

  // ドラッグ＆ドロップ：このカードを「つかめる」ようにする。編集中・書き込み中はつかめない（検索・絞り込み中はつかめる）
  // setNodeRef：どの要素がカードかを dnd-kit に教える
  // attributes・listeners：マウスやキーボードでつかむための仕掛け。カードの要素に付ける
  // transform・transition：ドラッグ中にカードをどれだけ動かして見せるか
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: task.id,
    disabled: editPosition !== null || busy,
  })

  // 編集中だけ、スクロールと画面の大きさの変化を見張り、そのたびにカードの位置を測り直す
  // （編集用のカードが、元のカードにくっついて動くようにするため。測るのが ✎ を押したときの1回だけだと、スクロールでずれる）
  const isEditing = editPosition !== null
  useEffect(() => {
    if (!isEditing) {
      return
    }
    function followCard() {
      const rect = cardRef.current?.getBoundingClientRect()
      if (rect) {
        setEditPosition(rect)
      }
    }
    // true：ページ全体だけでなく、ボードの横スクロールなど、中の部分のスクロールも拾う
    window.addEventListener('scroll', followCard, true)
    window.addEventListener('resize', followCard)
    // 後片付け：編集が終わったら見張りをやめる（残しておくと、編集していないのに測り続けてしまう）
    return () => {
      window.removeEventListener('scroll', followCard, true)
      window.removeEventListener('resize', followCard)
    }
  }, [isEditing])

  // 編集中は、幕の後ろ（画面の本体 #root）を inert（押せない・フォーカスが入らない）にする
  // 幕は見た目でクリックを止めるだけなので、Tab キーで後ろの ○・×・並び替えセレクトなどへ移って押せてしまう。
  // そうすると、編集中のカードの書き込みと重なったり、開いたときの版がずれたりする（設計書 6.1 節）
  // 編集画面は createPortal で body の直下に出しているので、#root の外にあり、inert にならない
  useEffect(() => {
    if (!isEditing) {
      return
    }
    const root = document.getElementById('root')
    if (root === null) {
      return
    }
    root.inert = true
    return () => {
      root.inert = false
    }
  }, [isEditing])

  // 編集が終わったとき（閉じた・カードの部品が消えた）に、Board に伝えて、カードを列・位置に留めるのをやめてもらう
  // 部品が消えたとき（例：取り直しで列そのものがなくなった）にも伝わるように、後片付けで呼ぶ
  // （閉じる関数の中だけで呼ぶと、部品が消えたときに伝わらず、カードがずっと古い位置に留められてしまう）
  useEffect(() => {
    if (!isEditing) {
      return
    }
    return () => onEditEnd()
  }, [isEditing, onEditEnd])

  // ✎ を押したとき：欄に今のタスクの値を入れ、版を控え、カードの位置を測ってから、編集を始める
  // カードの位置を測れないとき（部品がまだ画面にない）は、何もしない
  // （位置がないと編集画面を出せないのに、Board にだけ「始めた」と伝えると、閉じたときの「終わった」が届かず、
  //  カードが開いたときの列・位置に留められたままになるため）
  function startEditing() {
    const rect = cardRef.current?.getBoundingClientRect()
    if (!rect) {
      return
    }
    setText(task.text)
    setPriority(task.priority)
    setDueDate(task.dueDate ?? '')
    setBaseVersion(task.version)
    setConflict(null)
    setEditPosition(rect)
    onEditStart(task)
  }

  // 編集を閉じる（保存できた・カードが消えていた・キャンセル）。止められたときの「いまの内容」も捨てる
  // Board への「編集が終わった」の知らせは、上の後片付け（useEffect）が送る
  function finishEditing() {
    setEditPosition(null)
    setConflict(null)
  }

  // 保存の送信中は閉じない（閉じたあとに失敗すると、入力が消えたように見えるため）
  // Esc・幕のクリックで閉じたら、入力は捨てる（止められていたときも、利用者が閉じることを選んだため）
  function cancelEditing() {
    if (savingRef.current) {
      return
    }
    finishEditing()
  }

  // 「いまの内容を入れる」：欄を、ほかの画面で変更された いまの内容 に入れ替え、赤い枠を消す（フォームは開いたまま）
  // 版の控えは、止められたときに いまの内容 の版にしてあるので、このあと「保存」すれば、いまの内容に重ねて保存できる
  function fillWithCurrent() {
    if (conflict === null) {
      return
    }
    setText(conflict.text)
    setPriority(conflict.priority)
    setDueDate(conflict.dueDate ?? '')
    setConflict(null)
  }

  // × を押したとき：押し間違いで消えないよう、ブラウザの確認ダイアログを出す
  // confirm は、OK なら true、キャンセルなら false を返す。OK のときだけ削除を頼む
  function handleDeleteClick() {
    if (window.confirm(`「${task.text}」を削除しますか？`)) {
      onDelete(task.id)
    }
  }

  // 保存ボタンを押したとき、またはタスク名の欄で Enter キーを押したときに呼ばれる
  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault() // ページの再読み込みを止める
    if (savingRef.current) {
      return // 保存の送信中。2 回目（ボタン・Enter）は送らない
    }

    // タスク名が空欄や空白だけのときは、元のタスク名のままにする（要件定義書の試験14）
    const trimmed = text.trim()

    // 列（columnId）は送らない。サーバーは今の列のまま残す（TaskContent の説明を参照）
    // 版は控えておいたもの。止められたあとの「この入力で上書き保存」は、いまの内容の版で送る
    // （いまの内容を見たうえで、利用者が選んで上書きするため）
    savingRef.current = true
    setSaving(true)
    let result: UpdateResult = { kind: 'failed' }
    try {
      result = await onUpdate(
        task.id,
        {
          text: trimmed === '' ? task.text : trimmed,
          priority,
          dueDate: dueDate === '' ? null : dueDate,
        },
        baseVersion,
      )
    } finally {
      savingRef.current = false
      setSaving(false)
    }
    // 保存できた・カードが消えていた：編集を閉じる
    // ほかの画面で変更されていた：入力はそのまま残し、いまの内容を見せる。次の保存は、いまの内容の版で送る
    // そのほかの失敗：入力をそのまま残す（直して、もう一度保存できるように）
    if (result.kind === 'ok' || result.kind === 'gone') {
      finishEditing()
    } else if (result.kind === 'conflict') {
      setConflict(result.current)
      setBaseVersion(result.current.version)
    }
  }

  // タスク名の欄：Enter で保存、Esc でキャンセル（Shift + Enter は改行）
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSubmit()
    } else if (event.key === 'Escape') {
      cancelEditing()
    }
  }

  return (
    <>
      <div
        // 位置を測るための cardRef と、dnd-kit の setNodeRef の両方に、同じ要素を渡す
        ref={(node) => {
          cardRef.current = node
          setNodeRef(node)
        }}
        style={{
          transform: transform ? `translate3d(${transform.x}px, ${transform.y}px, 0)` : undefined,
          transition,
        }}
        {...attributes}
        {...listeners}
        aria-busy={busy}
        // ドラッグ中、元のカードは「着地点」として薄く表示する（マウスに付いてくるのは TaskCardOverlay）
        // 書き込み中（返事待ち）は、少し薄く（不透明度 60%）して、送信中だと分かるようにする。つかめないので、矢印のカーソルにする
        className={`group rounded bg-white p-3 shadow-sm ${busy ? 'cursor-default' : 'cursor-grab'} ${
          isDragging ? 'opacity-40' : busy ? 'opacity-60' : ''
        }`}
      >
        <div className="flex items-start gap-2">
          {/* ○：押すと完了の列へ移す。変えるのは列（columnId）だけなので PATCH を使う。完了の列のカードには出さない */}
          {/* ○・✎・× は、書き込み中は押せない（disabled） */}
          {doneColumnId !== null && task.columnId !== doneColumnId && (
            <button
              type="button"
              onClick={() => onPatch(task.id, { columnId: doneColumnId })}
              onKeyDown={stopKeyFromReachingCard}
              disabled={busy}
              aria-label="完了にする"
              title="完了にする"
              className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border border-gray-400 text-[10px] leading-none text-transparent hover:border-[#61bd4f] hover:bg-[#61bd4f] hover:text-white disabled:cursor-not-allowed disabled:hover:border-gray-400 disabled:hover:bg-transparent disabled:hover:text-transparent"
            >
              ✓
            </button>
          )}
          <p className="flex-1 text-sm whitespace-pre-wrap text-gray-800">{task.text}</p>
          <button
            type="button"
            onClick={startEditing}
            onKeyDown={stopKeyFromReachingCard}
            disabled={busy}
            aria-label="編集"
            className="shrink-0 rounded px-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-gray-400"
          >
            ✎
          </button>
          <button
            type="button"
            onClick={handleDeleteClick}
            onKeyDown={stopKeyFromReachingCard}
            disabled={busy}
            aria-label="削除"
            title="削除"
            className="shrink-0 rounded px-1 text-gray-400 hover:bg-gray-100 hover:text-[#eb5a46] disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-gray-400"
          >
            ×
          </button>
        </div>
        <CardLabels priority={task.priority} dueDate={task.dueDate} />
      </div>

      {/* 編集画面は、createPortal で body の直下（画面の一番外側）に出す */}
      {/* カードの内側に出すと、カードの見た目の設定（dnd-kit の transform など）に引きずられ、幕が画面全体を覆えなくなるため */}
      {editPosition &&
        createPortal(
          // 画面全体を暗くする幕。幕をクリックするとキャンセル
          <div className="fixed inset-0 z-50 bg-black/60" onClick={cancelEditing}>
            <div
              className="absolute flex items-start gap-2"
              style={{ top: editPosition.top, left: editPosition.left }}
              // 編集用のカードやメニューをクリックしても、幕のクリック（キャンセル）にならないようにする
              onClick={(event) => event.stopPropagation()}
            >
              {/* 左：編集用のカードと保存ボタン */}
              <form onSubmit={handleSubmit} style={{ width: editPosition.width }}>
                <div className="rounded bg-white p-3 shadow-sm">
                  <textarea
                    value={text}
                    onChange={(event) => setText(event.target.value)}
                    onKeyDown={handleKeyDown}
                    autoFocus
                    // 送信中は書き換えられない（disabled にするとフォーカスが外れるので readOnly）
                    readOnly={saving}
                    // バックエンドと同じく 255 文字まで（DB の列が 255 文字までのため）
                    maxLength={255}
                    rows={3}
                    className="w-full resize-none text-sm text-gray-800 outline-none"
                  />
                  <CardLabels priority={priority} dueDate={dueDate === '' ? null : dueDate} />
                </div>
                {/* ほかの画面で変更されていて、保存が止められたとき：赤い枠で知らせ、いまの内容を見せる（設計書 6.2 節） */}
                {conflict && (
                  <div role="alert" className="mt-2 rounded border-2 border-red-500 bg-red-50 p-2 text-xs text-red-800">
                    <p>
                      ほかの画面で、このカードが変更されていたため、保存しませんでした。いまの内容は下のとおりです。あなたの入力は、そのまま残しています。
                    </p>
                    <p className="mt-1 whitespace-pre-wrap">タスク名：{conflict.text}</p>
                    <p>優先度：{priorityLabels[conflict.priority]}</p>
                    <p>期限：{conflict.dueDate ?? 'なし'}</p>
                  </div>
                )}
                <div className="mt-2 flex flex-wrap gap-2">
                  {/* 止められたあとは「この入力で上書き保存」（いまの内容を見たうえで、自分の入力で上書きする） */}
                  <button
                    type="submit"
                    disabled={saving}
                    className="rounded bg-[#0079bf] px-4 py-1.5 text-sm text-white hover:bg-[#026aa7] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-[#0079bf]"
                  >
                    {saving ? '保存中…' : conflict ? 'この入力で上書き保存' : '保存'}
                  </button>
                  {conflict && (
                    <button
                      type="button"
                      onClick={fillWithCurrent}
                      disabled={saving}
                      className="rounded bg-white px-4 py-1.5 text-sm text-gray-800 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      いまの内容を入れる
                    </button>
                  )}
                </div>
              </form>

              {/* 右：メニュー（優先度・期限）。ここで変えた値は、保存ボタンを押したときにまとめて送る */}
              <div className="flex w-44 flex-col gap-2">
                <div className="rounded bg-black/70 p-2 text-sm text-white">
                  <p className="mb-1 text-xs text-gray-300">優先度</p>
                  <div className="flex gap-1">
                    {priorities.map((p) => (
                      <button
                        key={p}
                        type="button"
                        onClick={() => setPriority(p)}
                        disabled={saving}
                        className={`flex flex-1 items-center justify-center gap-1 rounded px-2 py-1 ${
                          priority === p ? 'bg-white text-gray-800' : 'hover:bg-white/20'
                        }`}
                      >
                        <span className={`inline-block h-2.5 w-2.5 rounded-full ${priorityColors[p]}`} />
                        {priorityLabels[p]}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="rounded bg-black/70 p-2 text-sm text-white">
                  <p className="mb-1 text-xs text-gray-300">期限</p>
                  <input
                    type="date"
                    value={dueDate}
                    onChange={(event) => setDueDate(event.target.value)}
                    disabled={saving}
                    className="w-full rounded bg-white px-2 py-1 text-gray-800"
                  />
                  {dueDate !== '' && (
                    <button
                      type="button"
                      onClick={() => setDueDate('')}
                      disabled={saving}
                      className="mt-1 w-full rounded px-2 py-1 text-left hover:bg-white/20"
                    >
                      期限を消す
                    </button>
                  )}
                </div>
              </div>
            </div>
          </div>,
          document.body,
        )}
    </>
  )
}

export default TaskCard
