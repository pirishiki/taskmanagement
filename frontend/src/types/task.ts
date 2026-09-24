// API（GET /api/tasks）から返ってくるタスク1件の形
// バックエンドの Task.java に対応する
export type Task = {
  id: number
  text: string
  status: 'todo' | 'doing' | 'done'
  priority: 'high' | 'medium' | 'low'
  dueDate: string | null
  sortOrder: number
}
