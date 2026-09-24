import { useState, type FormEvent } from 'react'
import type { NewTask, Task } from '../types/task'

type Props = {
  // status（どの列か）は Column が決めるので、ここでは受け取らない
  onAdd: (task: Omit<NewTask, 'status'>) => void
}

function AddTaskForm({ onAdd }: Props) {
  const [text, setText] = useState('')
  const [priority, setPriority] = useState<Task['priority']>('medium')
  const [dueDate, setDueDate] = useState('')

  // 追加ボタンを押したとき、またはタスク名の欄で Enter キーを押したときに呼ばれる
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める

    const trimmed = text.trim()
    if (trimmed === '') {
      return // 空欄や空白だけのときは何もしない（要件定義書どおり、エラーも出さない）
    }

    onAdd({ text: trimmed, priority, dueDate: dueDate === '' ? null : dueDate })

    // 次のタスクを続けて入力できるように、欄を最初の状態に戻す
    setText('')
    setPriority('medium')
    setDueDate('')
  }

  return (
    <form onSubmit={handleSubmit} className="mt-3 flex flex-col gap-2">
      <input
        type="text"
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder="タスクを追加"
        className="rounded border border-gray-300 bg-white px-3 py-2 text-sm"
      />
      <div className="flex gap-2">
        <select
          value={priority}
          onChange={(event) => setPriority(event.target.value as Task['priority'])}
          className="rounded border border-gray-300 bg-white px-2 py-1 text-sm"
        >
          <option value="high">高</option>
          <option value="medium">中</option>
          <option value="low">低</option>
        </select>
        <input
          type="date"
          value={dueDate}
          onChange={(event) => setDueDate(event.target.value)}
          className="min-w-0 flex-1 rounded border border-gray-300 bg-white px-2 py-1 text-sm"
        />
        <button type="submit" className="rounded bg-[#0079bf] px-3 py-1 text-sm text-white hover:bg-[#026aa7]">
          追加
        </button>
      </div>
    </form>
  )
}

export default AddTaskForm
