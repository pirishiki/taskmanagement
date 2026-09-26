import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, createTask, deleteTask, fetchTasks, patchTask, reorderTasks, updateTask } from './api/taskApi'
import Board from './components/Board'
import SearchBar from './components/SearchBar'
import type { NewTask, SortCriterion, Task, TaskPatch } from './types/task'

// 優先度順に並べるときの順位（数字が小さいほど上に来る）
const priorityRank: Record<Task['priority'], number> = {
  high: 0,
  medium: 1,
  low: 2,
}

// 失敗の種類（ApiError の status）に合わせて、画面に出すメッセージを作る
// action は「タスクを追加」のような、しようとしたこと
function errorMessage(error: unknown, action: string): string {
  if (!(error instanceof ApiError) || error.status === 0) {
    return `${action}できませんでした。バックエンドにつながりません。起動しているか確認してください。`
  }
  if (error.status === 400) {
    // 入力チェック違反なら errors の理由を、そうでなければ detail を、かっこの中に出す
    const reasons = Object.values(error.problem?.errors ?? {})
    const reason = reasons.length > 0 ? reasons.join('、') : error.problem?.detail
    return `${action}できませんでした。入力内容を確かめてください（${reason}）。`
  }
  if (error.status === 404) {
    return `${action}できませんでした。タスクが見つかりません（ほかの画面で削除された可能性があります）。`
  }
  return `${action}できませんでした。サーバーでエラーが起きました。時間をおいて試してください。`
}

// 「タスクが見つからない」（404）失敗かどうか
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 最後に検索したキーワード。空でなければ、一部のタスクだけを表示している（検索中）
  const [searchKeyword, setSearchKeyword] = useState('')

  // 最後に頼んだ取得の番号。返事が届いたとき、この番号と同じものだけを画面に使う
  // （検索を続けて押したとき、先に頼んだ古い結果があとから届いて、新しい結果を上書きしないようにするため）
  const latestRequest = useRef(0)

  // タスクを取ってきて、届いたら画面のメモ（tasks）を書き換える
  // useCallback：画面を描き直しても、同じ関数のまま使い回す（useEffect の依存に書いても、毎回動き直さないようにするため）
  const loadTasks = useCallback((keyword?: string) => {
    latestRequest.current += 1
    const requestId = latestRequest.current
    const isLatest = () => requestId === latestRequest.current

    fetchTasks(keyword)
      .then((result) => {
        if (!isLatest()) {
          return // もっと新しい取得を頼んであるので、この古い結果は捨てる
        }
        setTasks(result)
        setError(null)
      })
      .catch((error) => {
        if (!isLatest()) {
          return
        }
        setTasks([])
        setError(errorMessage(error, 'タスクを取得'))
      })
      .finally(() => {
        if (isLatest()) {
          setLoading(false)
        }
      })
  }, [])

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
      .catch((error) => {
        setError(errorMessage(error, 'タスクを追加'))
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
      .catch((error) => {
        setError(errorMessage(error, 'タスクを更新'))
        // もうないタスクなら、画面の一覧からも外す（画面と DB を合わせる）
        if (isNotFound(error)) {
          setTasks((prev) => prev.filter((t) => t.id !== id))
        }
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
      .catch((error) => {
        setError(errorMessage(error, 'タスクを更新'))
        // もうないタスクなら、画面の一覧からも外す（画面と DB を合わせる）
        if (isNotFound(error)) {
          setTasks((prev) => prev.filter((t) => t.id !== id))
        }
      })
  }

  // カードの × で削除が確かめられたときに呼ばれる（確認のダイアログはカードの側で出す）
  function handleDelete(id: number) {
    deleteTask(id)
      .then(() => {
        // 削除できたら、同じ id のタスクだけを一覧から外す（ほかはそのまま）
        setTasks((prev) => prev.filter((t) => t.id !== id))
        setError(null)
      })
      .catch((error) => {
        setError(errorMessage(error, 'タスクを削除'))
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
      .catch((error) => {
        setError(errorMessage(error, '並び替えを保存'))
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
      .catch((error) => {
        setError(errorMessage(error, '並び替えを保存'))
        loadTasks()
      })
  }

  // 最初の表示時に全件を取ってくる（loading は最初から true にしてある）
  useEffect(() => {
    loadTasks()
  }, [loadTasks])

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
        onDelete={handleDelete}
        onMove={handleMove}
        onSort={handleSort}
        // 検索中は、見えていないタスクと並び順がずれるのを防ぐため、ドラッグできないようにする
        canDrag={searchKeyword === ''}
      />
    </div>
  )
}

export default App
