import type { NewTask, Task } from '../types/task'

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
