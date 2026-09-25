import { useEffect, useState } from 'react'
import { createTask, fetchTasks, patchTask, reorderTasks, updateTask } from './api/taskApi'
import Board from './components/Board'
import SearchBar from './components/SearchBar'
import type { NewTask, SortCriterion, Task, TaskPatch } from './types/task'

// 優先度順に並べるときの順位（数字が小さいほど上に来る）
const priorityRank: Record<Task['priority'], number> = {
  high: 0,
  medium: 1,
  low: 2,
}

function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 最後に検索したキーワード。空でなければ、一部のタスクだけを表示している（検索中）
  const [searchKeyword, setSearchKeyword] = useState('')

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
    setSearchKeyword(keyword)
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

  // カードの ○（完了）が押されたときなどに呼ばれる（PATCH で一部だけ書き換える）
  function handlePatch(id: number, patch: TaskPatch) {
    patchTask(id, patch)
      .then((updated) => {
        // 返ってきたタスクには、移動先の列と並び順も入っている。同じ id のタスクだけを入れ替える
        setTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
        setError(null)
      })
      .catch(() => {
        setError('タスクを更新できませんでした。バックエンドが起動しているか確認してください。')
      })
  }

  // カードをドラッグ＆ドロップしたときに呼ばれる
  // taskId のタスクを、toStatus の列の上から toIndex 番目（0 から数える）に入れる
  function handleMove(taskId: number, toStatus: Task['status'], toIndex: number) {
    const moved = tasks.find((t) => t.id === taskId)
    if (!moved) {
      return
    }
    const fromStatus = moved.status

    // 列のタスクを sortOrder の小さい順に並べる
    const columnOf = (list: Task[], status: Task['status']) =>
      list.filter((t) => t.status === status).sort((a, b) => a.sortOrder - b.sortOrder)

    // 1. 動かすタスクを抜いた移動先の列に、toIndex の位置で差し込む
    const target = columnOf(tasks, toStatus).filter((t) => t.id !== taskId)
    target.splice(toIndex, 0, { ...moved, status: toStatus })

    // 2. 移動先の列の sortOrder を、上から 0, 1, 2… と振り直す
    const changed = new Map<number, Task>()
    target.forEach((t, i) => changed.set(t.id, { ...t, sortOrder: i }))

    // 3. 列をまたいだときは、移動元の列も詰め直す（抜けた穴をふさぐ）
    const source = fromStatus === toStatus ? [] : columnOf(tasks, fromStatus).filter((t) => t.id !== taskId)
    source.forEach((t, i) => changed.set(t.id, { ...t, sortOrder: i }))

    // 4. 画面を先に書き換える（サーバーの返事を待たずに、カードがすぐ動いて見える）
    setTasks((prev) => prev.map((t) => changed.get(t.id) ?? t))

    // 5. サーバーに新しい並び順を送る。失敗したら、DB の状態を取り直して画面を元に戻す
    const requests = [reorderTasks(toStatus, target.map((t) => t.id))]
    if (source.length > 0) {
      requests.push(reorderTasks(fromStatus, source.map((t) => t.id)))
    }
    Promise.all(requests)
      .then(() => setError(null))
      .catch(() => {
        setError('並び替えを保存できませんでした。バックエンドが起動しているか確認してください。')
        loadTasks()
      })
  }

  // 列の並び替えセレクトで「優先度順」「期限が近い順」を選んだときに呼ばれる
  // その列のカードを一度だけ並べ直す。そのあとは、またドラッグで自由に動かせる
  function handleSort(status: Task['status'], criterion: SortCriterion) {
    // 1. 列のタスクを、今の並び順（sortOrder の小さい順）に並べる
    const column = tasks.filter((t) => t.status === status).sort((a, b) => a.sortOrder - b.sortOrder)

    // 2. 選んだ基準で並べ直す。sort は、同じ順位どうしの順番を変えない（今の順番のまま残る）
    const sorted = [...column].sort((a, b) => {
      if (criterion === 'priority') {
        return priorityRank[a.priority] - priorityRank[b.priority]
      }
      // 期限が近い順：期限のないカードは一番下。日付は「2026-09-26」の形なので、文字の順がそのまま日付の順になる
      if (a.dueDate === null && b.dueDate === null) return 0
      if (a.dueDate === null) return 1
      if (b.dueDate === null) return -1
      return a.dueDate.localeCompare(b.dueDate)
    })

    // 3. sortOrder を上から 0, 1, 2… と振り直し、画面を先に書き換える
    const changed = new Map<number, Task>()
    sorted.forEach((t, i) => changed.set(t.id, { ...t, sortOrder: i }))
    setTasks((prev) => prev.map((t) => changed.get(t.id) ?? t))

    // 4. サーバーに新しい並び順を送る（ドラッグと同じ API）。失敗したら、DB の状態を取り直して画面を元に戻す
    reorderTasks(status, sorted.map((t) => t.id))
      .then(() => setError(null))
      .catch(() => {
        setError('並び替えを保存できませんでした。バックエンドが起動しているか確認してください。')
        loadTasks()
      })
  }

  // 最初の表示時に全件を取ってくる（loading は最初から true にしてある）
  useEffect(() => {
    loadTasks()
  }, [])

  return (
    <div className="min-h-screen bg-sky-100 p-6">
      <h1 className="mb-6 text-3xl font-bold text-[#1c3d5a]">Task Board</h1>
      <SearchBar onSearch={handleSearch} searching={searchKeyword !== ''} />
      {loading && <p className="mb-4 text-gray-600">読み込み中…</p>}
      {error && <p className="mb-4 text-red-600">{error}</p>}
      <Board
        tasks={tasks}
        onAdd={handleAdd}
        onUpdate={handleUpdate}
        onPatch={handlePatch}
        onMove={handleMove}
        onSort={handleSort}
        // 検索中は、見えていないタスクと並び順がずれるのを防ぐため、ドラッグできないようにする
        canDrag={searchKeyword === ''}
      />
    </div>
  )
}

export default App
