import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  createTask,
  deleteTask,
  exportTasks,
  fetchTasks,
  importTasks,
  moveTask,
  patchTask,
  reorderTasks,
  updateTask,
} from './api/taskApi'
import Board from './components/Board'
import DataButtons from './components/DataButtons'
import SearchBar from './components/SearchBar'
import { noFilters, type NewTask, type SortCriterion, type Task, type TaskFilters, type TaskPatch } from './types/task'

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

// その列（status）のタスクだけを取り出し、sortOrder の小さい順（上から順）に並べる
function columnOf(tasks: Task[], status: Task['status']): Task[] {
  return tasks.filter((t) => t.status === status).sort((a, b) => a.sortOrder - b.sortOrder)
}

// 「タスクが見つからない」（404）失敗かどうか
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

function App() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 最後に検索したときの条件（キーワード・優先度・期限）
  const [filters, setFilters] = useState<TaskFilters>(noFilters)
  // 条件が1つでも選ばれていれば、一部のタスクだけを表示している（検索・絞り込み中）
  const isFiltering = filters.keyword !== '' || filters.priorities.length > 0 || filters.due !== ''

  // 最後に頼んだ取得の番号。返事が届いたとき、この番号と同じものだけを画面に使う
  // （検索を続けて押したとき、先に頼んだ古い結果があとから届いて、新しい結果を上書きしないようにするため）
  const latestRequest = useRef(0)

  // タスクを取ってきて、届いたら画面のメモ（tasks）を書き換える
  // 成功しても、エラーのメッセージは消さない（並び替えの失敗のあとで取り直したとき、失敗のメッセージを残すため）
  // useCallback：画面を描き直しても、同じ関数のまま使い回す（useEffect の依存に書いても、毎回動き直さないようにするため）
  // filters を渡さなければ、全件を取る
  const loadTasks = useCallback((filters?: TaskFilters) => {
    latestRequest.current += 1
    const requestId = latestRequest.current
    const isLatest = () => requestId === latestRequest.current

    fetchTasks(filters)
      .then((result) => {
        if (!isLatest()) {
          return // もっと新しい取得を頼んであるので、この古い結果は捨てる
        }
        setTasks(result)
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

  // 検索ボタンか Enter キーで呼ばれる（「検索を解除」のときは、何も選ばれていない条件が来る）
  // 前のエラーのメッセージは、ここで消す（取り直しに失敗すれば、loadTasks がまた出す）
  function handleSearch(nextFilters: TaskFilters) {
    setError(null)
    setLoading(true)
    setFilters(nextFilters)
    loadTasks(nextFilters)
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
  // 検索・絞り込み中でも使える。tasks には見えているカードしかないが、番号はサーバーが見えていないカードも含めて決める
  function handleMove(taskId: number, toStatus: Task['status'], toIndex: number) {
    const moved = tasks.find((t) => t.id === taskId)
    if (!moved) {
      return
    }

    // 1. 同じ列の同じ位置に落としたときは、何もしない
    if (moved.status === toStatus && columnOf(tasks, toStatus).findIndex((t) => t.id === taskId) === toIndex) {
      return
    }

    // 2. 動かすタスクを抜いた移動先の列で、落とした位置のすぐ上（above）とすぐ下（below）のカードを探す
    const target = columnOf(tasks, toStatus).filter((t) => t.id !== taskId)
    const above = target[toIndex - 1] ?? null // 一番上に落としたら、上のカードはない
    const below = target[toIndex] ?? null // 一番下に落としたら、下のカードはない

    // 3. 画面を先に書き換える（サーバーの返事を待たずに、カードがすぐ動いて見える）
    // 仮の番号は、見えている上下のカードの真ん中。正しい番号は、サーバーの返事で置き換える
    let sortOrder = 0
    if (above && below) {
      sortOrder = (above.sortOrder + below.sortOrder) / 2
    } else if (above) {
      sortOrder = above.sortOrder + 1
    } else if (below) {
      sortOrder = below.sortOrder - 1
    }
    setTasks((prev) => prev.map((t) => (t.id === taskId ? { ...t, status: toStatus, sortOrder } : t)))

    // 4. サーバーに「above のすぐ下に入れて」と頼む。動かしたカードの番号だけが変わる
    // 失敗したら、今の条件で DB の状態を取り直して、画面を元に戻す
    moveTask(taskId, toStatus, above?.id ?? null)
      .then((updated) => {
        setTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
        setError(null)
      })
      .catch((error) => {
        setError(errorMessage(error, '並び替えを保存'))
        loadTasks(filters)
      })
  }

  // 列の並び替えセレクトで「優先度順」「期限が近い順」を選んだときに呼ばれる
  // その列の見えているカードを一度だけ並べ直す。そのあとは、またドラッグで自由に動かせる
  // 見えているカードどうしで席（番号）を入れ替えるので、検索・絞り込み中でも、見えていないカードの順番はずれない
  function handleSort(status: Task['status'], criterion: SortCriterion) {
    // 1. 列のタスクを、今の並び順（sortOrder の小さい順）に並べる。上から順の番号が、そのまま「席」になる
    const column = columnOf(tasks, status)
    const seats = column.map((t) => t.sortOrder)

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

    // 3. 並べ直した順に、上の席から座ってもらい、画面を先に書き換える
    const changed = new Map<number, Task>()
    sorted.forEach((t, i) => changed.set(t.id, { ...t, sortOrder: seats[i] }))
    setTasks((prev) => prev.map((t) => changed.get(t.id) ?? t))

    // 4. サーバーに新しい順番を送る（サーバーも同じ席の入れ替えをする）
    // 失敗したら、今の条件で DB の状態を取り直して、画面を元に戻す
    reorderTasks(status, sorted.map((t) => t.id))
      .then(() => setError(null))
      .catch((error) => {
        setError(errorMessage(error, '並び替えを保存'))
        loadTasks(filters)
      })
  }

  // 「⬇ 書き出す」が押されたときに呼ばれる。全部のタスクを JSON のファイルとして保存させる
  function handleExport() {
    exportTasks()
      .then(() => setError(null))
      .catch((error) => {
        setError(errorMessage(error, 'タスクを書き出し'))
      })
  }

  // 「⬆ 読み込む」でファイルが選ばれ、確認で OK が押されたときに呼ばれる
  // 今のタスクを全部、ファイルのタスクに置き換える。成功したら、今の条件で一覧を取り直す
  // 失敗したときは、サーバーが何も変えていないので、画面もそのままでよい
  function handleImport(file: File) {
    importTasks(file)
      .then(() => {
        setError(null)
        loadTasks(filters)
      })
      .catch((error) => {
        setError(errorMessage(error, 'ファイルを読み込み'))
      })
  }

  // 最初の表示時に全件を取ってくる（loading は最初から true にしてある）
  useEffect(() => {
    loadTasks()
  }, [loadTasks])

  return (
    <div className="min-h-screen bg-sky-100 p-6">
      {/* 指で操作する画面（スマホ等）のときだけ表示する。PC（マウス）では hidden のまま */}
      <p className="mb-4 hidden rounded bg-yellow-100 p-3 text-yellow-900 pointer-coarse:block">
        スマートフォンでは閲覧のみ可能です。タスクの追加・削除・移動はPCで行ってください
      </p>
      {/* 見出しの右に、書き出し・読み込みのボタンを並べる（幅が狭いときは下に回る） */}
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-3xl font-bold text-[#1c3d5a]">Task Board</h1>
        <DataButtons onExport={handleExport} onImport={handleImport} />
      </div>
      <SearchBar onSearch={handleSearch} searching={isFiltering} />
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
      />
    </div>
  )
}

export default App
