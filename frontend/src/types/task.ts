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

// タスクの一部だけを書き換えるとき（PATCH /api/tasks/{id}）に送る中身
// Partial は「全部の項目を、書いても書かなくてもよいことにする」という意味
// 丸ごと書き換えるとき（PUT）は、登録と同じ NewTask を送る
export type TaskPatch = Partial<NewTask>

// 列の並び替えセレクトで選べる基準（'priority'：優先度順、'dueDate'：期限が近い順）
export type SortCriterion = 'priority' | 'dueDate'
