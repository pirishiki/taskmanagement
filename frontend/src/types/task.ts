// API（GET /api/tasks）から返ってくるタスク1件の形
// バックエンドの Task.java に対応する
export type Task = {
  id: number
  text: string
  // どの列にいるか（列の番号。BoardColumn の id）
  columnId: number
  priority: 'high' | 'medium' | 'low'
  dueDate: string | null
  sortOrder: number
}

// タスクを登録するとき（POST /api/tasks）に送る中身
// id と sortOrder はバックエンドが決めるので、ここには入れない
export type NewTask = {
  text: string
  columnId: number
  priority: Task['priority']
  dueDate: string | null
}

// タスクの中身（タスク名・優先度・期限）。NewTask から、どの列か（columnId）を除いたもの
// Omit は「指定した項目を取り除いた型を作る」という意味
// 使う場面：
// ・カードの追加フォーム（列は、フォームが置かれた Column が書き足す）
// ・クイック編集の保存（PUT /api/tasks/{id}）。列は送らない。送らなければ、サーバーは今の列のまま残す
//   （画面が覚えている列は古いことがある。別のタブや端末でカードを動かしたあとに送ると、元の列に戻してしまうため）
export type TaskContent = Omit<NewTask, 'columnId'>

// タスクの一部だけを書き換えるとき（PATCH /api/tasks/{id}）に送る中身
// Partial は「全部の項目を、書いても書かなくてもよいことにする」という意味
export type TaskPatch = Partial<NewTask>

// 列の並び替えセレクトで選べる基準（'priority'：優先度順、'dueDate'：期限が近い順）
export type SortCriterion = 'priority' | 'dueDate'

// 期限での絞り込み（'overdue'：期限切れ、'week'：7日以内、'none'：期限なし、''：期限で絞らない）
// バックエンドの DueFilter.java に対応する
export type DueFilter = 'overdue' | 'week' | 'none' | ''

// タスクを取るとき（GET /api/tasks）の絞り込みの条件。全部を満たすタスクだけが返る（AND）
// keyword が ''、priorities が []、due が '' のときは、その条件では絞らない
export type TaskFilters = {
  keyword: string
  priorities: Task['priority'][]
  due: DueFilter
}

// 何も絞り込んでいないときの条件（最初の表示と、「検索を解除」を押したとき）
export const noFilters: TaskFilters = { keyword: '', priorities: [], due: '' }
