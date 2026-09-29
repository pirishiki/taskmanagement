import type { NewTask, Task, TaskFilters, TaskPatch } from '../types/task'

// エラーの返事（ProblemDetail）の中身。バックエンドの GlobalExceptionHandler が作る
// errors は、入力チェック違反のときだけ入る（項目名 → メッセージ）
export type ProblemDetail = {
  title?: string
  status?: number
  detail?: string
  errors?: Record<string, string>
}

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

// fetch でバックエンドを呼び、失敗したら ApiError を投げる。どの API もこれを通す
async function request(url: string, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch {
    throw new ApiError(0, null) // 通信そのものができなかった
  }
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
  throw new ApiError(response.status, problem)
}

// JSON を送るときの共通の設定
const jsonHeaders = { 'Content-Type': 'application/json' }

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
export async function createTask(task: NewTask): Promise<Task> {
  const response = await request('/api/tasks', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(task),
  })
  return response.json()
}

// タスクを丸ごと書き換える（PUT）。書き換え後のタスクが返る
export async function updateTask(id: number, task: NewTask): Promise<Task> {
  const response = await request(`/api/tasks/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(task),
  })
  return response.json()
}

// タスクの一部だけを書き換える（PATCH）。書き換え後のタスクが返る
export async function patchTask(id: number, patch: TaskPatch): Promise<Task> {
  const response = await request(`/api/tasks/${id}`, {
    method: 'PATCH',
    headers: jsonHeaders,
    body: JSON.stringify(patch),
  })
  return response.json()
}

// タスクを削除する（DELETE）。返ってくる中身はない（204）
// 404（そのタスクはもうない）も成功として扱う。別のタブなどで先に消されていても、「消したい」という目的は果たせているため
export async function deleteTask(id: number): Promise<void> {
  try {
    await request(`/api/tasks/${id}`, { method: 'DELETE' })
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return
    }
    throw error
  }
}

// ドラッグ＆ドロップ用：カード（id）を、status の列の、prevId のカードのすぐ下に入れる
// prevId は、落とした位置のすぐ上に見えているカードの ID。一番上に落としたときは null
// 番号はサーバーが決める（見えていないカードも含めて、上のカードとすぐ下のカードの真ん中）。動かしたあとのタスクが返る
export async function moveTask(id: number, status: Task['status'], prevId: number | null): Promise<Task> {
  const response = await request(`/api/tasks/${id}/move`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ status, prevId }),
  })
  return response.json()
}

// 自動並び替え（優先度順・期限が近い順）用：1つの列の中で、orderedIds のカードどうしの席（番号）を入れ替える
// 送らなかったカード（絞り込みで見えていないカード）の番号は変わらない。返ってくる中身はない（204）
export async function reorderTasks(status: Task['status'], orderedIds: number[]): Promise<void> {
  await request('/api/tasks/reorder', {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ status, orderedIds }),
  })
}
