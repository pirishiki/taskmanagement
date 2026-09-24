import { useEffect, useState } from 'react'
import { createTask, fetchTasks, updateTask } from './api/taskApi'
import Board from './components/Board'
import SearchBar from './components/SearchBar'
import type { NewTask, Task } from './types/task'

function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // タスクを取ってきて、届いたら画面のメモ（tasks）を書き換える
  function loadTasks(keyword?: string) {
    fetchTasks(keyword)
      .then((result) => {
        setTasks(result)
        setError(null)
      })
      .catch(() => {
        setTasks([])
        setError('タスクを取得できませんでした。バックエンドが起動しているか確認してください。')
      })
      .finally(() => setLoading(false))
  }

  // 検索ボタンか Enter キーで呼ばれる
  function handleSearch(keyword: string) {
    setLoading(true)
    loadTasks(keyword)
  }

  // 列のフォームで「追加」が押されたときに呼ばれる
  function handleAdd(task: NewTask) {
    createTask(task)
      .then((created) => {
        // 今のタスク一覧の最後に、登録されたタスクを足す
        setTasks((prev) => [...prev, created])
        setError(null)
      })
      .catch(() => {
        setError('タスクを追加できませんでした。バックエンドが起動しているか確認してください。')
      })
  }

  // カードの編集フォームで「保存」が押されたときに呼ばれる（PUT で丸ごと書き換える）
  function handleUpdate(id: number, task: NewTask) {
    updateTask(id, task)
      .then((updated) => {
        // 同じ id のタスクだけを、返ってきたタスクに入れ替える（ほかはそのまま）
        setTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
        setError(null)
      })
      .catch(() => {
        setError('タスクを更新できませんでした。バックエンドが起動しているか確認してください。')
      })
  }

  // 最初の表示時に全件を取ってくる（loading は最初から true にしてある）
  useEffect(() => {
    loadTasks()
  }, [])

  return (
    <div className="min-h-screen bg-sky-100 p-6">
      <h1 className="mb-6 text-3xl font-bold text-[#1c3d5a]">Task Board</h1>
      <SearchBar onSearch={handleSearch} />
      {loading && <p className="mb-4 text-gray-600">読み込み中…</p>}
      {error && <p className="mb-4 text-red-600">{error}</p>}
      <Board tasks={tasks} onAdd={handleAdd} onUpdate={handleUpdate} />
    </div>
  )
}

export default App
