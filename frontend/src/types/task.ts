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

// タスクを登録するとき（POST /api/tasks）に送る中身
// id と sortOrder はバックエンドが決めるので、ここには入れない
export type NewTask = {
  text: string
  status: Task['status']
  priority: Task['priority']
  dueDate: string | null
}
