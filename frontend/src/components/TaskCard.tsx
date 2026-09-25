import { useSortable } from '@dnd-kit/sortable'
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import type { NewTask, Task, TaskPatch } from '../types/task'

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

type Props = {
  task: Task
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  canDrag: boolean
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

function TaskCard({ task, onUpdate, onPatch, onDelete, canDrag }: Props) {
  // 編集中のカードを出す位置。null のあいだは編集していない
  // 編集中のカードに目が向くように、画面を暗くして、その上の同じ位置に編集用のカードを重ねる
  const [editPosition, setEditPosition] = useState<DOMRect | null>(null)
  const [text, setText] = useState(task.text)
  const [priority, setPriority] = useState<Task['priority']>(task.priority)
  const [dueDate, setDueDate] = useState(task.dueDate ?? '')
  const cardRef = useRef<HTMLDivElement>(null)

  // ドラッグ＆ドロップ：このカードを「つかめる」ようにする。編集中と検索中（canDrag が false）はつかめない
  // setNodeRef：どの要素がカードかを dnd-kit に教える
  // attributes・listeners：マウスやキーボードでつかむための仕掛け。カードの要素に付ける
  // transform・transition：ドラッグ中にカードをどれだけ動かして見せるか
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: task.id,
    disabled: editPosition !== null || !canDrag,
  })

  // ✎ を押したとき：欄に今のタスクの値を入れ、カードの位置を測ってから、編集を始める
  function startEditing() {
    setText(task.text)
    setPriority(task.priority)
    setDueDate(task.dueDate ?? '')
    setEditPosition(cardRef.current?.getBoundingClientRect() ?? null)
  }

  function cancelEditing() {
    setEditPosition(null)
  }

  // × を押したとき：押し間違いで消えないよう、ブラウザの確認ダイアログを出す
  // confirm は、OK なら true、キャンセルなら false を返す。OK のときだけ削除を頼む
  function handleDeleteClick() {
    if (window.confirm(`「${task.text}」を削除しますか？`)) {
      onDelete(task.id)
    }
  }

  // 保存ボタンを押したとき、またはタスク名の欄で Enter キーを押したときに呼ばれる
  function handleSubmit(event?: FormEvent) {
    event?.preventDefault() // ページの再読み込みを止める

    // タスク名が空欄や空白だけのときは、元のタスク名のままにする（要件定義書の試験14）
    const trimmed = text.trim()

    // PUT は丸ごと書き換えるので、変えていない status も今の値のまま送る
    onUpdate(task.id, {
      text: trimmed === '' ? task.text : trimmed,
      status: task.status,
      priority,
      dueDate: dueDate === '' ? null : dueDate,
    })
    setEditPosition(null)
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
        // ドラッグ中、元のカードは「着地点」として薄く表示する（マウスに付いてくるのは TaskCardOverlay）
        className={`group rounded bg-white p-3 shadow-sm ${canDrag ? 'cursor-grab' : ''} ${isDragging ? 'opacity-40' : ''}`}
      >
        <div className="flex items-start gap-2">
          {/* ○：押すと完了にする。変えるのは status だけなので PATCH を使う。「終わったこと」の列では出さない */}
          {task.status !== 'done' && (
            <button
              type="button"
              onClick={() => onPatch(task.id, { status: 'done' })}
              aria-label="完了にする"
              title="完了にする"
              className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border border-gray-400 text-[10px] leading-none text-transparent hover:border-[#61bd4f] hover:bg-[#61bd4f] hover:text-white"
            >
              ✓
            </button>
          )}
          <p className="flex-1 text-sm whitespace-pre-wrap text-gray-800">{task.text}</p>
          <button
            type="button"
            onClick={startEditing}
            aria-label="編集"
            className="shrink-0 rounded px-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700"
          >
            ✎
          </button>
          <button
            type="button"
            onClick={handleDeleteClick}
            aria-label="削除"
            title="削除"
            className="shrink-0 rounded px-1 text-gray-400 hover:bg-gray-100 hover:text-[#eb5a46]"
          >
            ×
          </button>
        </div>
        <CardLabels priority={task.priority} dueDate={task.dueDate} />
      </div>

      {editPosition && (
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
                  rows={3}
                  className="w-full resize-none text-sm text-gray-800 outline-none"
                />
                <CardLabels priority={priority} dueDate={dueDate === '' ? null : dueDate} />
              </div>
              <button
                type="submit"
                className="mt-2 rounded bg-[#0079bf] px-4 py-1.5 text-sm text-white hover:bg-[#026aa7]"
              >
                保存
              </button>
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
                  className="w-full rounded bg-white px-2 py-1 text-gray-800"
                />
                {dueDate !== '' && (
                  <button
                    type="button"
                    onClick={() => setDueDate('')}
                    className="mt-1 w-full rounded px-2 py-1 text-left hover:bg-white/20"
                  >
                    期限を消す
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

export default TaskCard
