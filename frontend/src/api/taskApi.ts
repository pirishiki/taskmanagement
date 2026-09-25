import type { NewTask, Task, TaskPatch } from '../types/task'

// タスクを取得する。keyword を渡すと、タスク名にそれを含むものだけが返る
export async function fetchTasks(keyword?: string): Promise<Task[]> {
  const params = new URLSearchParams()
  if (keyword) {
    params.set('keyword', keyword)
  }

  const response = await fetch(`/api/tasks?${params}`)
  if (!response.ok) {
    throw new Error(`タスクの取得に失敗しました（status ${response.status}）`)
  }
  return response.json()
}

// タスクを登録する。登録が終わると、id と sortOrder の付いたタスクが返る
export async function createTask(task: NewTask): Promise<Task> {
  const response = await fetch('/api/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(task),
  })
  if (!response.ok) {
    throw new Error(`タスクの登録に失敗しました（status ${response.status}）`)
  }
  return response.json()
}

// タスクを丸ごと書き換える（PUT）。書き換え後のタスクが返る
export async function updateTask(id: number, task: NewTask): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(task),
  })
  if (!response.ok) {
    throw new Error(`タスクの更新に失敗しました（status ${response.status}）`)
  }
  return response.json()
}

// タスクの一部だけを書き換える（PATCH）。書き換え後のタスクが返る
export async function patchTask(id: number, patch: TaskPatch): Promise<Task> {
  const response = await fetch(`/api/tasks/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  })
  if (!response.ok) {
    throw new Error(`タスクの更新に失敗しました（status ${response.status}）`)
  }
  return response.json()
}

// タスクを削除する（DELETE）。返ってくる中身はない（204）
// 404（そのタスクはもうない）も成功として扱う。別のタブなどで先に消されていても、「消したい」という目的は果たせているため
export async function deleteTask(id: number): Promise<void> {
  const response = await fetch(`/api/tasks/${id}`, {
    method: 'DELETE',
  })
  if (!response.ok && response.status !== 404) {
    throw new Error(`タスクの削除に失敗しました（status ${response.status}）`)
  }
}

// 1つの列の並び順を、orderedIds の順番どおりにする。返ってくる中身はない（204）
export async function reorderTasks(status: Task['status'], orderedIds: number[]): Promise<void> {
  const response = await fetch('/api/tasks/reorder', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, orderedIds }),
  })
  if (!response.ok) {
    throw new Error(`並び替えに失敗しました（status ${response.status}）`)
  }
}
