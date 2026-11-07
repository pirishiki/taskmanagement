import type { NewTask, Task, TaskContent, TaskFilters, TaskPatch } from '../types/task'
import { boardIdHeaders, markBoardChanged, observeBoardId } from './boardId'

// エラーの返事（ProblemDetail）の中身。バックエンドの GlobalExceptionHandler が作る
// errors は、入力チェック違反のときだけ入る（項目名 → メッセージ）
// code は、同じ 409 の理由を見分ける印（docs/同時編集の設計書.md の 4.2 節・5 節）
//   'VERSION_CONFLICT'：ほかの画面で先に変更されていた（版がちがう）。current に、そのタスク・列のいまの状態が入ることがある
//   'BOARD_CHANGED'：DB が作り直されている（ボードの番号がちがう）
//   なし：そのほかの 409（タスクが入っている列は消せない、など）
export type ProblemDetail = {
  title?: string
  status?: number
  detail?: string
  errors?: Record<string, string>
  code?: string
  current?: unknown
}

// 書き込み（ボードの番号を一緒に送る通信）の種類
const writeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

// API の呼び出しが失敗したときに投げる
// status が 0 のときは、バックエンドにつながらなかった（止まっている、など）
export class ApiError extends Error {
  status: number
  problem: ProblemDetail | null

  constructor(status: number, problem: ProblemDetail | null) {
    super(problem?.detail ?? `API の呼び出しに失敗しました（status ${status}）`)
    this.status = status
    this.problem = problem
  }
}

// 版のずれ（409 の VERSION_CONFLICT）の失敗かどうか
export function isVersionConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409 && error.problem?.code === 'VERSION_CONFLICT'
}

// DB が作り直されていた（409 の BOARD_CHANGED）失敗かどうか
export function isBoardChanged(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.problem?.code === 'BOARD_CHANGED'
}

// 版のずれの失敗についてきた「いまの状態」（current）。ついていなければ null
// （何枚ものカードを対象にする並べ替えと、DB の安全網の 409 には、ついてこない）
export function conflictCurrent<T>(error: unknown): T | null {
  if (!isVersionConflict(error) || error.problem?.current == null) {
    return null
  }
  return error.problem.current as T
}

// fetch でバックエンドを呼び、失敗したら ApiError を投げる。どの API もこれを通す（列の API の columnApi.ts も使う）
// ボードの番号（boardId.ts）もここで扱う：書き込みには覚えた番号を付けて送り、返事の番号は毎回確かめる
export async function request(url: string, init?: RequestInit): Promise<Response> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers = new Headers(init?.headers)
  if (writeMethods.has(method)) {
    Object.entries(boardIdHeaders()).forEach(([name, value]) => headers.set(name, value))
  }

  let response: Response
  try {
    response = await fetch(url, { ...init, headers })
  } catch {
    throw new ApiError(0, null) // 通信そのものができなかった
  }
  // 返事の番号が、覚えた番号とちがえば「DB が作り直された」と知らせる（読み取りの返事でも。設計書 6.1 節）
  observeBoardId(response)
  if (response.ok) {
    return response
  }

  // バックエンドのエラーの返事は、必ず ProblemDetail（application/problem+json）
  // そうでない返事は、バックエンドまで届かなかった（Vite のプロキシがつなげなかった、など）とみなす
  const isProblem = response.headers.get('Content-Type')?.includes('application/problem+json') ?? false
  if (!isProblem) {
    throw new ApiError(0, null)
  }
  const problem: ProblemDetail = await response.json()
  if (response.status === 409 && problem.code === 'BOARD_CHANGED') {
    markBoardChanged()
  }
  throw new ApiError(response.status, problem)
}

// JSON を送るときの共通の設定
export const jsonHeaders = { 'Content-Type': 'application/json' }

// タスクを取得する。filters を渡すと、その条件を全部満たすものだけが返る（渡さなければ全件）
// 例：{ keyword: '', priorities: ['high', 'medium'], due: 'overdue' } → /api/tasks?priority=high&priority=medium&due=overdue
export async function fetchTasks(filters?: TaskFilters): Promise<Task[]> {
  const params = new URLSearchParams()
  if (filters?.keyword) {
    params.set('keyword', filters.keyword)
  }
  // priority は、選ばれた優先度の数だけ書く（set は上書き、append は後ろに足す）
  filters?.priorities.forEach((priority) => params.append('priority', priority))
  if (filters?.due) {
    params.set('due', filters.due)
  }
  const response = await request(`/api/tasks?${params}`)
  return response.json()
}

// タスクを登録する。登録が終わると、id と sortOrder の付いたタスクが返る
// idempotencyKey は番号札（UUID）。ヘッダー Idempotency-Key で送る
// 同じ札で送り直しても、サーバーは新しく作らず、1 回目と同じ結果を返す
export async function createTask(task: NewTask, idempotencyKey: string): Promise<Task> {
  const response = await request('/api/tasks', {
    method: 'POST',
    headers: { ...jsonHeaders, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(task),
  })
  return response.json()
}

// ここから下の書き込みは、どれも version（画面が見ていた版）を一緒に送る（docs/同時編集の設計書.md の 4.1 節）
// サーバーの今の版とちがえば、何も書かれずに 409（VERSION_CONFLICT）の ApiError になる

// タスクの中身（タスク名・優先度・期限）を丸ごと書き換える（PUT）。書き換え後のタスクが返る
// 列（columnId）は送らないので、タスクは今の列のまま（types/task.ts の TaskContent を参照）
export async function updateTask(id: number, task: TaskContent, version: number): Promise<Task> {
  const response = await request(`/api/tasks/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ ...task, version }),
  })
  return response.json()
}

// タスクの一部だけを書き換える（PATCH）。書き換え後のタスクが返る
export async function patchTask(id: number, patch: TaskPatch, version: number): Promise<Task> {
  const response = await request(`/api/tasks/${id}`, {
    method: 'PATCH',
    headers: jsonHeaders,
    body: JSON.stringify({ ...patch, version }),
  })
  return response.json()
}

// タスクを削除する（DELETE /api/tasks/{id}?version=…）。返ってくる中身はない（204）
// 404（そのタスクはもうない）も成功として扱う。別のタブなどで先に消されていても、「消したい」という目的は果たせているため
export async function deleteTask(id: number, version: number): Promise<void> {
  try {
    await request(`/api/tasks/${id}?version=${version}`, { method: 'DELETE' })
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return
    }
    throw error
  }
}

// ドラッグ＆ドロップ用：カード（id）を、columnId の列の、prevId のカードのすぐ下に入れる
// prevId は、落とした位置のすぐ上に見えているカードの ID。一番上に落としたときは null
// 番号はサーバーが決める（見えていないカードも含めて、上のカードとすぐ下のカードの真ん中）。動かしたあとのタスクが返る
// version は、動かすカードの版（prevId のカードの版は送らない）
export async function moveTask(id: number, columnId: number, prevId: number | null, version: number): Promise<Task> {
  const response = await request(`/api/tasks/${id}/move`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ columnId, prevId, version }),
  })
  return response.json()
}

// 自動並び替え（優先度順・期限が近い順）用：1つの列の中で、orderedIds のカードどうしの席（番号）を入れ替える
// 送らなかったカード（絞り込みで見えていないカード）の番号は変わらない
// versions は orderedIds と同じ順の版。1 枚でもちがえば、どのカードも動かずに 409
// 並べ直したタスク（新しい sortOrder・version 入り。もう消えていたものは入らない）が、orderedIds の順で返る
export async function reorderTasks(columnId: number, orderedIds: number[], versions: number[]): Promise<Task[]> {
  const response = await request('/api/tasks/reorder', {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ columnId, orderedIds, versions }),
  })
  return response.json()
}

// 全部のタスクを、JSON のファイルとしてパソコンに保存させる（書き出し）
// 1. 返事の中身を Blob（ファイルの中身のかたまり）として受け取る
// 2. その Blob を指す一時的な URL を作り、画面に出さない <a download> をクリックして保存させる
// 3. 一時的な URL を片付ける
// ファイル名は、返事の Content-Disposition の札（attachment; filename="tasks-2026-09-29.json"）から取る
export async function exportTasks(): Promise<void> {
  const response = await request('/api/tasks/export')
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const fileName = /filename="([^"]+)"/.exec(disposition)?.[1] ?? 'tasks.json'

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

// 選ばれたファイルを読み込み、今のタスクを全部そのファイルのタスクに置き換える。返ってくる中身はない（204）
// 中身が正しいかどうかのチェックはサーバーに任せ、ファイルの文字をそのまま送る
// 1件でもおかしければ 400（ApiError）になり、今のタスクは変わらない
export async function importTasks(file: File): Promise<void> {
  await request('/api/tasks/import', {
    method: 'POST',
    headers: jsonHeaders,
    body: await file.text(),
  })
}
