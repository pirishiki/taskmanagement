import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  conflictCurrent,
  createTask,
  deleteTask,
  exportTasks,
  fetchTasks,
  importTasks,
  isBoardChanged,
  isVersionConflict,
  moveTask,
  patchTask,
  reorderTasks,
  updateTask,
} from './api/taskApi'
import { onBoardChanged } from './api/boardId'
import { createColumn, deleteColumn, fetchColumns } from './api/columnApi'
import Board from './components/Board'
import BoardChangedBanner from './components/BoardChangedBanner'
import DataButtons from './components/DataButtons'
import SearchBar from './components/SearchBar'
import type { BoardColumn } from './types/column'
import {
  noFilters,
  type AddResult,
  type NewTask,
  type SortCriterion,
  type Task,
  type TaskContent,
  type TaskFilters,
  type TaskPatch,
  type UpdateResult,
} from './types/task'
import { fitsBetween } from './utils/sortOrder'

// 優先度順に並べるときの順位（数字が小さいほど上に来る）
const priorityRank: Record<Task['priority'], number> = {
  high: 0,
  medium: 1,
  low: 2,
}

// ほかの画面で先に変更されていた（409 の VERSION_CONFLICT）ときのメッセージ（docs/同時編集の設計書.md の 6.2 節）
// 画面は自動で最新の内容に入れ替える（current か、取り直し）ので、そう伝える
const staleTaskUpdateMessage =
  'タスクを更新できませんでした。ほかの画面で変更されていたため、最新の内容に入れ替えました。確かめてから、もう一度操作してください。'
const staleTaskDeleteMessage =
  'タスクを削除できませんでした。ほかの画面で変更されていたため、最新の内容に入れ替えました。確かめてから、もう一度操作してください。'
const staleSortMessage =
  '並び替えを保存できませんでした。ほかの画面で変更されていたため、最新の状態を読み込み直しました。確かめてから、もう一度操作してください。'
const staleColumnDeleteMessage =
  '列を削除できませんでした。ほかの画面で変更されていたため、最新の内容に入れ替えました。確かめてから、もう一度操作してください。'

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
    return `${action}できませんでした。見つかりません（ほかの画面で削除された可能性があります）。`
  }
  if (error.status === 503) {
    // 別の書き込みが続いていて待ちきれなかった。何も書き込んでいないので、そのまま送り直してよい。理由は detail に入っている
    return `${action}できませんでした（${error.problem?.detail ?? 'いま別の書き込みが続いています'}）。`
  }
  if (error.status === 409) {
    // 今の状態ではできない（例：タスクが入っている列を消そうとした・DB が作り直されている）。理由は detail に入っている
    return `${action}できませんでした（${error.problem?.detail}）。`
  }
  return `${action}できませんでした。サーバーでエラーが起きました。時間をおいて試してください。`
}

// その列（columnId）のタスクだけを取り出し、sortOrder の小さい順（上から順）に並べる
function columnOf(tasks: Task[], columnId: number): Task[] {
  return tasks.filter((t) => t.columnId === columnId).sort((a, b) => a.sortOrder - b.sortOrder)
}

// 一覧の中の同じ id のものだけを、item に入れ替える（ほかはそのまま。一覧になければ何もしない）
function replaceById<T extends { id: number }>(list: T[], item: T): T[] {
  return list.map((x) => (x.id === item.id ? item : x))
}

// 一覧に同じ id があれば入れ替え、なければ最後に足す
function upsertById<T extends { id: number }>(list: T[], item: T): T[] {
  return list.some((x) => x.id === item.id) ? replaceById(list, item) : [...list, item]
}

// 「タスクが見つからない」（404）失敗かどうか
function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404
}

// ドラッグ・自動並び替えの失敗が「ほかの画面での変更」によるものか（docs/同時編集の設計書.md の 6.2 節）
// 版のずれ（409）と、400・404（画面は正しい入力しか送らないので、ほかの画面で上のカードが動いた・消えた、列が消えた、でしか起きない）
function isChangedElsewhere(error: unknown): boolean {
  return isVersionConflict(error) || (error instanceof ApiError && (error.status === 400 || error.status === 404))
}

// 追加の失敗のメッセージ。404 は、前に同じ札で作ったものが消されている（追加の API で 404 になるのはこの場合だけ）
function addErrorMessage(error: unknown, action: string): string {
  if (error instanceof ApiError && error.status === 404) {
    return `${action}できませんでした。前に送ったものは消されています。もう一度押すと、新しく作ります。`
  }
  return errorMessage(error, action)
}

// 追加が失敗したとき、フォームに伝える結果を決める
// 404（前に同じ札で作ったものが消されている）・409（同じ札で中身がちがう・DB が作り直されている）は、同じ札で送り直しても必ず同じ失敗になる。
// そのため番号札を取り替えさせる。それ以外（通信できない・503 〔札の行はあるが番号がまだない。待てば直る〕など）は、同じ札のまま送り直せる
function addFailureResult(error: unknown): AddResult {
  if (error instanceof ApiError && (error.status === 404 || error.status === 409)) {
    return 'newKey'
  }
  return 'failed'
}

function App() {
  // 列の一覧（左から順）。ボードに並べる列は、ここに入っているものだけ
  const [columns, setColumns] = useState<BoardColumn[]>([])
  const [tasks, setTasks] = useState<Task[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // DB が作り直された（ボードの番号が変わった）と分かったら true。黄色の帯を出し、ページを読み込み直すまで消さない
  const [boardChanged, setBoardChanged] = useState(false)
  // 最後に検索したときの条件（キーワード・優先度・期限）
  const [filters, setFilters] = useState<TaskFilters>(noFilters)
  // 最新の検索の条件。通信が終わったあとの取り直しは、押した時点の古い条件（クロージャ）ではなく、これを使う
  // （import や並び替えの通信中に検索されても、検索欄の新しい条件で取り直すため）
  const filtersRef = useRef<TaskFilters>(noFilters)
  // 条件が1つでも選ばれていれば、一部のタスクだけを表示している（検索・絞り込み中）
  const isFiltering = filters.keyword !== '' || filters.priorities.length > 0 || filters.due !== ''

  // 最後に頼んだ取得の番号。返事が届いたとき、この番号と同じものだけを画面に使う
  // （検索を続けて押したとき、先に頼んだ古い結果があとから届いて、新しい結果を上書きしないようにするため）
  const latestRequest = useRef(0)
  const latestColumnsRequest = useRef(0)

  // 書き込みの返事を画面に入れた回数（タスク・列）。取り直しの決まり（docs/同時編集の設計書.md の 6.1 節）に使う
  // 取り直しを頼んでから返事が届くまでに、この数が増えていたら、その返事は自分の書き込みより前の状態かもしれない
  // （例：検索の通信中にカードを足すと、検索の返事には足したカードがなく、画面から消えて見える。版も古くなる）
  // そのときは、返事を使わずに、もう一度取り直す
  const taskWriteEpoch = useRef(0)
  const columnWriteEpoch = useRef(0)

  // 書き込み中のタスク・列（返事が来るまで、そのカードの ○・✎・×・ドラッグ、列の × を押せなくする。設計書 6.1 節）
  // 理由：返事の前に 2 本目を送ると、2 本目は古い版になり、自分の画面どうしで「ほかの画面で変更されました」とまちがって止まる
  // ref は今すぐの確かめ用、state は見た目（薄く表示・押せない）用。タスクは数を数える（同じカードが 2 つの書き込みに入っても、
  // 両方が終わるまで書き込み中のままにするため）
  const busyTaskCounts = useRef(new Map<number, number>())
  const [busyTaskIds, setBusyTaskIds] = useState<ReadonlySet<number>>(() => new Set())
  const busyColumns = useRef(new Set<number>())
  const [busyColumnIds, setBusyColumnIds] = useState<ReadonlySet<number>>(() => new Set())

  function isTaskBusy(id: number): boolean {
    return busyTaskCounts.current.has(id)
  }

  function markTasksBusy(ids: number[]) {
    ids.forEach((id) => busyTaskCounts.current.set(id, (busyTaskCounts.current.get(id) ?? 0) + 1))
    setBusyTaskIds(new Set(busyTaskCounts.current.keys()))
  }

  function unmarkTasksBusy(ids: number[]) {
    ids.forEach((id) => {
      const count = (busyTaskCounts.current.get(id) ?? 0) - 1
      if (count <= 0) {
        busyTaskCounts.current.delete(id)
      } else {
        busyTaskCounts.current.set(id, count)
      }
    })
    setBusyTaskIds(new Set(busyTaskCounts.current.keys()))
  }

  function setColumnBusy(id: number, busy: boolean) {
    if (busy) {
      busyColumns.current.add(id)
    } else {
      busyColumns.current.delete(id)
    }
    setBusyColumnIds(new Set(busyColumns.current))
  }

  // 書き込みの返事（成功したもの、または current つきの 409）を、画面のタスクの一覧に入れる
  // 入れた回数を数えて、通信中の取り直しの返事を使わないようにする（上の taskWriteEpoch）
  function applyTaskWrite(update: (prev: Task[]) => Task[]) {
    taskWriteEpoch.current += 1
    setTasks(update)
  }

  function applyColumnWrite(update: (prev: BoardColumn[]) => BoardColumn[]) {
    columnWriteEpoch.current += 1
    setColumns(update)
  }

  // タスクを取ってきて、届いたら画面のメモ（tasks）を書き換える
  // 成功しても、エラーのメッセージは消さない（並び替えの失敗のあとで取り直したとき、失敗のメッセージを残すため）
  // useCallback：画面を描き直しても、同じ関数のまま使い回す（useEffect の依存に書いても、毎回動き直さないようにするため）
  // filters を渡さなければ、全件を取る
  const loadTasks = useCallback((filters?: TaskFilters): Promise<void> => {
    function attempt(): Promise<void> {
      latestRequest.current += 1
      const requestId = latestRequest.current
      const isLatest = () => requestId === latestRequest.current
      const epochAtStart = taskWriteEpoch.current

      return fetchTasks(filters)
        .then((result) => {
          if (!isLatest()) {
            return // もっと新しい取得を頼んであるので、この古い結果は捨てる
          }
          if (taskWriteEpoch.current !== epochAtStart) {
            // 取り直しの決まり：通信中に、この画面の書き込みの返事を入れた。この返事はそれより前の状態かもしれないので、
            // 使わずに、同じ条件でもう一度取り直す（読み込み中の表示は、取り直しが終わるまで続く）
            return attempt()
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
    }
    return attempt()
  }, [])

  // 列の一覧を取ってきて、画面のメモ（columns）を書き換える（タスクと同じく、最後に頼んだものだけを使い、取り直しの決まりも守る）
  const loadColumns = useCallback((): Promise<void> => {
    function attempt(): Promise<void> {
      latestColumnsRequest.current += 1
      const requestId = latestColumnsRequest.current
      const isLatest = () => requestId === latestColumnsRequest.current
      const epochAtStart = columnWriteEpoch.current

      return fetchColumns()
        .then((result) => {
          if (!isLatest()) {
            return
          }
          if (columnWriteEpoch.current !== epochAtStart) {
            return attempt()
          }
          setColumns(result)
        })
        .catch((error) => {
          if (isLatest()) {
            setError(errorMessage(error, '列を取得'))
          }
        })
    }
    return attempt()
  }, [])

  // ドラッグ・自動並び替えが「ほかの画面での変更」で止められたとき：知らせて、今の条件で列とタスクを取り直す
  function reloadAfterStaleSort() {
    setError(staleSortMessage)
    loadColumns()
    loadTasks(filtersRef.current)
  }

  // 検索ボタンか Enter キーで呼ばれる（「検索を解除」のときは、何も選ばれていない条件が来る）
  // 前のエラーのメッセージは、ここで消す（取り直しに失敗すれば、loadTasks がまた出す）
  function handleSearch(nextFilters: TaskFilters) {
    setError(null)
    setLoading(true)
    setFilters(nextFilters)
    filtersRef.current = nextFilters
    loadTasks(nextFilters)
  }

  // 列のフォームで「追加」が押されたときに呼ばれる
  // 結果（AddResult）を返し、フォームが「欄を空にする／番号札を取り替える」を決める
  function handleAdd(task: NewTask, idempotencyKey: string): Promise<AddResult> {
    return createTask(task, idempotencyKey)
      .then((created): AddResult => {
        // 今のタスク一覧の最後に、登録されたタスクを足す
        // 同じ id のタスクがすでに一覧にあれば（返事が届かず取り直したあとの送り直し）、足さずに入れ替える
        applyTaskWrite((prev) => upsertById(prev, created))
        setError(null)
        return 'ok'
      })
      .catch((error): AddResult => {
        setError(addErrorMessage(error, 'タスクを追加'))
        return addFailureResult(error)
      })
  }

  // カードの編集フォームで「保存」が押されたときに呼ばれる（PUT で丸ごと書き換える）
  // version は、編集フォームが控えている版（✎ を開いたときの版。止められたあとは、いまの内容の版）
  // 結果（UpdateResult）を返し、編集フォームが「閉じる／入力を残す／いまの内容を見せる」を決める
  function handleUpdate(id: number, task: TaskContent, version: number): Promise<UpdateResult> {
    if (isTaskBusy(id)) {
      // ほかの書き込み（○・ドラッグなど）の返事を待っている。いま送ると、まちがって止まる（版が古くなる）ので送らない
      // 入力は残し、理由を出す（押しても何も起きない、にしない）
      setError('タスクを更新できませんでした。このカードは保存中です。終わってから、もう一度保存してください。')
      return Promise.resolve({ kind: 'failed' })
    }
    markTasksBusy([id])
    return updateTask(id, task, version)
      .then((updated): UpdateResult => {
        // 同じ id のタスクだけを、返ってきたタスクに入れ替える（ほかはそのまま）
        applyTaskWrite((prev) => replaceById(prev, updated))
        setError(null)
        return { kind: 'ok' }
      })
      .catch((error): UpdateResult => {
        // ほかの画面で先に変更されていた：後ろのカードを、いまの内容に入れ替える（絞り込みから外れても、次に検索するまで見えたまま）
        // 編集フォームは閉じず、入力を残して、いまの内容を見せる（設計書 6.2 節）
        const current = conflictCurrent<Task>(error)
        if (current !== null) {
          applyTaskWrite((prev) => upsertById(prev, current))
          return { kind: 'conflict', current }
        }
        if (isNotFound(error)) {
          // もうないタスク：画面の一覧からも外す（フォームも閉じる）。入力していたタスク名を出す（もう一度追加できるように）
          setError(
            `タスクを更新できませんでした。このカードは、ほかの画面で削除されていました（入力していたタスク名：「${task.text}」）。`,
          )
          applyTaskWrite((prev) => prev.filter((t) => t.id !== id))
          return { kind: 'gone' }
        }
        setError(errorMessage(error, 'タスクを更新'))
        return { kind: 'failed' }
      })
      .finally(() => unmarkTasksBusy([id]))
  }

  // カードの ○（完了）が押されたときなどに呼ばれる（PATCH で一部だけ書き換える）
  function handlePatch(id: number, patch: TaskPatch) {
    const task = tasks.find((t) => t.id === id)
    if (!task || isTaskBusy(id)) {
      return // 書き込み中のカードの ○ は押せない（ボタンも押せない見た目にしてある）
    }
    markTasksBusy([id])
    patchTask(id, patch, task.version)
      .then((updated) => {
        // 返ってきたタスクには、移動先の列と並び順も入っている。同じ id のタスクだけを入れ替える
        applyTaskWrite((prev) => replaceById(prev, updated))
        setError(null)
      })
      .catch((error) => {
        if (isVersionConflict(error)) {
          // ほかの画面で先に変更されていた：カードをいまの内容に入れ替える（完了の列へは移さない）
          const current = conflictCurrent<Task>(error)
          if (current !== null) {
            applyTaskWrite((prev) => replaceById(prev, current))
          } else {
            loadTasks(filtersRef.current) // いまの内容がついてこなかった（DB の安全網）ので、取り直して入れ替える
          }
          setError(staleTaskUpdateMessage)
          return
        }
        setError(errorMessage(error, 'タスクを更新'))
        // もうないタスクなら、画面の一覧からも外す（画面と DB を合わせる）
        if (isNotFound(error)) {
          applyTaskWrite((prev) => prev.filter((t) => t.id !== id))
        }
      })
      .finally(() => unmarkTasksBusy([id]))
  }

  // カードの × で削除が確かめられたときに呼ばれる（確認のダイアログはカードの側で出す）
  function handleDelete(id: number) {
    const task = tasks.find((t) => t.id === id)
    if (!task || isTaskBusy(id)) {
      return // 書き込み中（削除の 2 回押しなど）。2 本目は送らない
    }
    markTasksBusy([id])
    // すでにない番号（404）は api 側で成功として扱われるので、ここには成功と同じ形で届く
    deleteTask(id, task.version)
      .then(() => {
        // 削除できたら、同じ id のタスクだけを一覧から外す（ほかはそのまま）
        applyTaskWrite((prev) => prev.filter((t) => t.id !== id))
        setError(null)
      })
      .catch((error) => {
        if (isVersionConflict(error)) {
          // ほかの画面で直されていた：消さずに、いまの内容に入れ替える（もう一度 × を押すと、新しい名前で確認が出る）
          const current = conflictCurrent<Task>(error)
          if (current !== null) {
            applyTaskWrite((prev) => replaceById(prev, current))
          } else {
            loadTasks(filtersRef.current)
          }
          setError(staleTaskDeleteMessage)
          return
        }
        setError(errorMessage(error, 'タスクを削除'))
      })
      .finally(() => unmarkTasksBusy([id]))
  }

  // カードをドラッグ＆ドロップしたときに呼ばれる
  // taskId のタスクを、toColumnId の列の上から toIndex 番目（0 から数える）に入れる
  // 検索・絞り込み中でも使える。tasks には見えているカードしかないが、番号はサーバーが見えていないカードも含めて決める
  function handleMove(taskId: number, toColumnId: number, toIndex: number) {
    const moved = tasks.find((t) => t.id === taskId)
    if (!moved || isTaskBusy(taskId)) {
      return // 書き込み中のカードは、つかめないようにしてある
    }

    // 1. 同じ列の同じ位置に落としたときは、何もしない
    if (
      moved.columnId === toColumnId &&
      columnOf(tasks, toColumnId).findIndex((t) => t.id === taskId) === toIndex
    ) {
      return
    }

    // 2. 動かすタスクを抜いた移動先の列で、落とした位置のすぐ上（above）とすぐ下（below）のカードを探す
    const target = columnOf(tasks, toColumnId).filter((t) => t.id !== taskId)
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
    setTasks((prev) => prev.map((t) => (t.id === taskId ? { ...t, columnId: toColumnId, sortOrder } : t)))

    // 4. サーバーに「above のすぐ下に入れて」と頼む（動かすカードの版も送る）。動かしたカードの番号だけが変わる
    markTasksBusy([taskId])
    moveTask(taskId, toColumnId, above?.id ?? null, moved.version)
      .then((updated) => {
        applyTaskWrite((prev) => replaceById(prev, updated))
        setError(null)
        // ドラッグのあとの確かめ（設計書 6.1 節）：返ってきた番号が、落とした位置のすぐ上・すぐ下のカードの番号の間に入らない
        // ＝サーバーが列の番号を振り直した、ほかの画面で並べ替えられた、などで、画面の番号が古い。メッセージは出さずに取り直す
        if (!fitsBetween(updated.sortOrder, above, below)) {
          loadTasks(filtersRef.current)
        }
      })
      .catch((error) => {
        if (isChangedElsewhere(error)) {
          reloadAfterStaleSort()
          return
        }
        setError(errorMessage(error, '並び替えを保存'))
        // そのほかの失敗（つながらない・503 など）：今までどおり、今の条件で DB の状態を取り直して、画面を元に戻す
        // ただし DB が作り直されていたときは取り直さない（新しい DB のタスクが古い列に混ざって見えるため。黄色の帯で読み込み直してもらう）
        if (!isBoardChanged(error)) {
          loadTasks(filtersRef.current)
        }
      })
      .finally(() => unmarkTasksBusy([taskId]))
  }

  // 列の並び替えセレクトで「優先度順」「期限が近い順」を選んだときに呼ばれる
  // その列の見えているカードを一度だけ並べ直す。そのあとは、またドラッグで自由に動かせる
  // 見えているカードどうしで席（番号）を入れ替えるので、検索・絞り込み中でも、見えていないカードの順番はずれない
  function handleSort(columnId: number, criterion: SortCriterion) {
    // 1. 列のタスクを、今の並び順（sortOrder の小さい順）に並べる。上から順の番号が、そのまま「席」になる
    const column = columnOf(tasks, columnId)
    if (column.length === 0 || column.some((t) => isTaskBusy(t.id))) {
      return // 並べるカードがない、または書き込み中のカードがある（セレクトも押せない見た目にしてある）
    }
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

    // 4. サーバーに新しい順番と、それぞれの版を送る（サーバーも同じ席の入れ替えをする）。送ったカードは全部、書き込み中にする
    // 返ってきた並べ直したタスク（新しい番号と版）で入れ替える
    const ids = sorted.map((t) => t.id)
    markTasksBusy(ids)
    reorderTasks(columnId, ids, sorted.map((t) => t.version))
      .then((reordered) => {
        const byId = new Map(reordered.map((t) => [t.id, t]))
        applyTaskWrite((prev) => prev.map((t) => byId.get(t.id) ?? t))
        setError(null)
      })
      .catch((error) => {
        if (isChangedElsewhere(error)) {
          reloadAfterStaleSort()
          return
        }
        setError(errorMessage(error, '並び替えを保存'))
        if (!isBoardChanged(error)) {
          loadTasks(filtersRef.current) // ドラッグと同じく、DB が作り直されていたときは取り直さない
        }
      })
      .finally(() => unmarkTasksBusy(ids))
  }

  // 「＋ 列を追加」のフォームで「列を追加」が押されたときに呼ばれる。足した列は右端に入る
  function handleAddColumn(name: string, idempotencyKey: string): Promise<AddResult> {
    return createColumn(name, idempotencyKey)
      .then((created): AddResult => {
        // 同じ id の列がすでにあれば、足さずに入れ替える（タスクの追加と同じ）
        applyColumnWrite((prev) => upsertById(prev, created))
        setError(null)
        return 'ok'
      })
      .catch((error): AddResult => {
        setError(addErrorMessage(error, '列を追加'))
        return addFailureResult(error)
      })
  }

  // 列の × で削除が確かめられたときに呼ばれる（確認のダイアログは列の側で出す）
  // タスクが入っている列なら、サーバーが断る（409）。そのときは理由を画面に出し、列はそのまま残す
  function handleDeleteColumn(id: number) {
    const column = columns.find((c) => c.id === id)
    if (!column || busyColumns.current.has(id)) {
      return // 同じ列の削除が通信中（2 回押し）。2 本目は送らない（× も押せない見た目にしてある）
    }
    setColumnBusy(id, true)
    deleteColumn(id, column.version)
      .then(() => {
        applyColumnWrite((prev) => prev.filter((c) => c.id !== id))
        setError(null)
      })
      .catch((error) => {
        if (isVersionConflict(error)) {
          // ほかの画面で名前が変えられていた：消さずに、いまの内容に入れ替える（もう一度 × で、新しい名前の確認が出る）
          const current = conflictCurrent<BoardColumn>(error)
          if (current !== null) {
            applyColumnWrite((prev) => replaceById(prev, current))
          } else {
            loadColumns()
          }
          setError(staleColumnDeleteMessage)
          return
        }
        setError(errorMessage(error, '列を削除'))
      })
      .finally(() => setColumnBusy(id, false))
  }

  // 「⬇ 書き出す」が押されたときに呼ばれる。全部のタスクを JSON のファイルとして保存させる
  // 終わる（成功・失敗とも）と解決する Promise を返す。ボタンの「送信中」の表示に使う
  function handleExport(): Promise<void> {
    return exportTasks()
      .then(() => setError(null))
      .catch((error) => {
        setError(errorMessage(error, 'タスクを書き出し'))
      })
  }

  // 「⬆ 読み込む」でファイルが選ばれ、確認で OK が押されたときに呼ばれる
  // 今の列とタスクを全部、ファイルの列とタスクに置き換える。成功したら、列と、今の条件でのタスクを取り直す
  // 失敗したときは、サーバーが何も変えていないので、画面もそのままでよい
  // 終わる（成功・失敗とも）と解決する Promise を返す。ボタンの「送信中」の表示に使う
  function handleImport(file: File): Promise<void> {
    return importTasks(file)
      .then(async () => {
        setError(null)
        // 列とタスクの取り直しが終わるまで待つ（ボタンが先に戻ると、古い列へ追加して 400 になるため）
        // 取り直しの失敗は、loadColumns・loadTasks がそれぞれ画面に出す
        await Promise.all([loadColumns(), loadTasks(filtersRef.current)])
      })
      .catch((error) => {
        setError(errorMessage(error, 'ファイルを読み込み'))
      })
  }

  // 最初の表示時に、列の一覧とタスクの全件を取ってくる（loading は最初から true にしてある）
  useEffect(() => {
    loadColumns()
    loadTasks()
  }, [loadColumns, loadTasks])

  // ボードの番号が変わった（返事の Board-Id がちがう、または書き込みが 409 の BOARD_CHANGED）と知らされたら、黄色の帯を出す
  // 返す関数は、画面が消えるときの後片付け（知らせの登録をやめる）
  useEffect(() => onBoardChanged(() => setBoardChanged(true)), [])

  return (
    <div className="min-h-screen bg-sky-100 p-6">
      {/* 指で操作する画面（スマホ等）のときだけ表示する。PC（マウス）では hidden のまま */}
      <p className="mb-4 hidden rounded bg-yellow-100 p-3 text-yellow-900 pointer-coarse:block">
        スマートフォンでは閲覧のみ可能です。タスクの追加・削除・移動はPCで行ってください
      </p>
      {boardChanged && <BoardChangedBanner />}
      {/* 見出しの右に、書き出し・読み込みのボタンを並べる（幅が狭いときは下に回る） */}
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-3xl font-bold text-[#1c3d5a]">Task Board</h1>
        <DataButtons onExport={handleExport} onImport={handleImport} />
      </div>
      <SearchBar onSearch={handleSearch} searching={isFiltering} />
      {loading && <p className="mb-4 text-gray-600">読み込み中…</p>}
      {error && <p className="mb-4 text-red-600">{error}</p>}
      <Board
        columns={columns}
        tasks={tasks}
        busyTaskIds={busyTaskIds}
        busyColumnIds={busyColumnIds}
        onAdd={handleAdd}
        onUpdate={handleUpdate}
        onPatch={handlePatch}
        onDelete={handleDelete}
        onMove={handleMove}
        onSort={handleSort}
        onAddColumn={handleAddColumn}
        onDeleteColumn={handleDeleteColumn}
      />
    </div>
  )
}

export default App
