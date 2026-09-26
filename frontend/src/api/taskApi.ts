import type { NewTask, Task, TaskPatch } from '../types/task'

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

// タスクを取得する。keyword を渡すと、タスク名にそれを含むものだけが返る
export async function fetchTasks(keyword?: string): Promise<Task[]> {
  const params = new URLSearchParams()
  if (keyword) {
    params.set('keyword', keyword)
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

// 1つの列の並び順を、orderedIds の順番どおりにする。返ってくる中身はない（204）
export async function reorderTasks(status: Task['status'], orderedIds: number[]): Promise<void> {
  await request('/api/tasks/reorder', {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ status, orderedIds }),
  })
}
