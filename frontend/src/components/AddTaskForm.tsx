import { useState, type FormEvent, type KeyboardEvent } from 'react'
import type { NewTask, Task } from '../types/task'

// 優先度の選択肢。画面に出す <option> も、選ばれた値の確かめも、この一覧から作る
const priorityOptions: { value: Task['priority']; label: string }[] = [
  { value: 'high', label: '高' },
  { value: 'medium', label: '中' },
  { value: 'low', label: '低' },
]

type Props = {
  // status（どの列か）は Column が決めるので、ここでは受け取らない
  onAdd: (task: Omit<NewTask, 'status'>) => void
}

function AddTaskForm({ onAdd }: Props) {
  // フォームが開いているかどうか。画面をすっきり見せるため、ふだんは「＋ カードを追加」だけを出す
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const [priority, setPriority] = useState<Task['priority']>('medium')
  const [dueDate, setDueDate] = useState('')

  // 欄を最初の状態に戻す
  function resetFields() {
    setText('')
    setPriority('medium')
    setDueDate('')
  }

  // ✕ ボタン、または Esc キーでフォームを閉じる（入力途中の内容は捨てる）
  function close() {
    resetFields()
    setOpen(false)
  }

  // 追加ボタンを押したとき、またはタスク名の欄で Enter キーを押したときに呼ばれる
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める

    const trimmed = text.trim()
    if (trimmed === '') {
      return // 空欄や空白だけのときは何もしない（要件定義書どおり、エラーも出さない）
    }

    onAdd({ text: trimmed, priority, dueDate: dueDate === '' ? null : dueDate })

    // 次のタスクを続けて入力できるように、フォームは開いたまま欄だけを戻す
    resetFields()
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
        // バックエンドと同じく 255 文字まで（DB の列が 255 文字までのため）
        maxLength={255}
        placeholder="このカードのタイトルを入力…"
        className="rounded bg-white px-3 py-2 text-sm shadow-sm outline-none"
      />
      <div className="flex gap-2">
        <select
          value={priority}
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
          onChange={(event) => setDueDate(event.target.value)}
          className="min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-1 text-sm"
        />
      </div>
      <div className="flex items-center gap-2">
        <button type="submit" className="rounded bg-[#0079bf] px-3 py-1.5 text-sm text-white hover:bg-[#026aa7]">
          カードを追加
        </button>
        <button
          type="button"
          onClick={close}
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
