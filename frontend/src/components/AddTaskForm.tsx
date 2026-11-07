import { useState, type FormEvent, type KeyboardEvent } from 'react'
import { useIdempotentSubmit } from '../hooks/useIdempotentSubmit'
import type { AddResult, Task, TaskContent } from '../types/task'

// 優先度の選択肢。画面に出す <option> も、選ばれた値の確かめも、この一覧から作る
const priorityOptions: { value: Task['priority']; label: string }[] = [
  { value: 'high', label: '高' },
  { value: 'medium', label: '中' },
  { value: 'low', label: '低' },
]

type Props = {
  // columnId（どの列か）は Column が決めるので、ここでは受け取らない
  // 番号札（idempotencyKey）は useIdempotentSubmit が作って渡す。結果で、欄を空にするかが決まる
  onAdd: (task: TaskContent, idempotencyKey: string) => Promise<AddResult>
}

function AddTaskForm({ onAdd }: Props) {
  // フォームが開いているかどうか。画面をすっきり見せるため、ふだんは「＋ カードを追加」だけを出す
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const [priority, setPriority] = useState<Task['priority']>('medium')
  const [dueDate, setDueDate] = useState('')
  // 番号札と送信中の管理（送信中は 2 回目の送信を無視する）
  const { sending, submit, submitError } =useIdempotentSubmit(onAdd)

  // 欄を最初の状態に戻す
  function resetFields() {
    setText('')
    setPriority('medium')
    setDueDate('')
  }

  // ✕ ボタン、または Esc キーでフォームを閉じる（入力途中の内容は捨てる）
  // 送信中は閉じない（閉じたあとに失敗すると、捨てたはずの入力が戻ってしまうため）
  function close() {
    if (sending) {
      return
    }
    resetFields()
    setOpen(false)
  }

  // 追加ボタンを押したとき、またはタスク名の欄で Enter キーを押したときに呼ばれる
  async function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める

    const trimmed = text.trim()
    if (trimmed === '') {
      return // 空欄や空白だけのときは何もしない（要件定義書どおり、エラーも出さない）
    }

    const task = { text: trimmed, priority, dueDate: dueDate === '' ? null : dueDate }
    // 内容（文章・優先度・期限）が同じなら同じ番号札で送り直し、ちがえば新しい番号札になる
    const result = await submit(task, JSON.stringify(task))

    // 欄を空にするのは、追加が成功したあとだけ。失敗したときは入力をそのまま残す（送り直せるように）
    // 次のタスクを続けて入力できるように、フォームは開いたまま欄だけを戻す
    if (result === 'ok') {
      resetFields()
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      close()
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-2 w-full rounded px-2 py-1.5 text-left text-sm text-gray-600 hover:bg-gray-300/60 hover:text-gray-800"
      >
        ＋ カードを追加
      </button>
    )
  }

  return (
    <form onSubmit={handleSubmit} className="mt-2 flex flex-col gap-2">
      <input
        type="text"
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={handleKeyDown}
        autoFocus
        // 送信中は書き換えられない（disabled にすると入力欄からフォーカスが外れるので readOnly）
        readOnly={sending}
        // バックエンドと同じく 255 文字まで（DB の列が 255 文字までのため）
        maxLength={255}
        placeholder="このカードのタイトルを入力…"
        className="rounded bg-white px-3 py-2 text-sm shadow-sm outline-none"
      />
      <div className="flex gap-2">
        <select
          value={priority}
          disabled={sending}
          onChange={(event) => {
            // 選ばれた値を選択肢の一覧から探す。見つかれば、その値は必ず 'high' | 'medium' | 'low' のどれか
            const selected = priorityOptions.find((option) => option.value === event.target.value)
            if (selected) {
              setPriority(selected.value)
            }
          }}
          className="rounded border border-gray-300 bg-white px-2 py-1 text-sm"
        >
          {priorityOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <input
          type="date"
          value={dueDate}
          disabled={sending}
          onChange={(event) => setDueDate(event.target.value)}
          className="min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-1 text-sm"
        />
      </div>
      {/* 番号札が作れなかったときなど、フォームの中で出す赤いメッセージ */}
      {submitError && <p className="text-sm text-red-600">{submitError}</p>}
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={sending}
          className="rounded bg-[#0079bf] px-3 py-1.5 text-sm text-white hover:bg-[#026aa7] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-[#0079bf]"
        >
          {sending ? '追加中…' : 'カードを追加'}
        </button>
        <button
          type="button"
          onClick={close}
          disabled={sending}
          aria-label="閉じる"
          className="rounded px-2 py-1 text-lg leading-none text-gray-500 hover:bg-gray-300/60 hover:text-gray-800"
        >
          ✕
        </button>
      </div>
    </form>
  )
}

export default AddTaskForm
