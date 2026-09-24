import type { Task } from '../types/task'

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
