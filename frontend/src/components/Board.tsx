import type { NewTask, Task, TaskPatch } from '../types/task'
import Column from './Column'

// 列の並び順と見出し（試作版 index.html と同じ）
const columns: { status: Task['status']; title: string }[] = [
  { status: 'todo', title: 'やるべきこと' },
  { status: 'doing', title: '進行中' },
  { status: 'done', title: '終わったこと' },
]

type Props = {
  tasks: Task[]
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
}

function Board({ tasks, onAdd, onUpdate, onPatch }: Props) {
  return (
    <div className="flex items-start gap-4 overflow-x-auto">
      {columns.map((column) => (
        <Column
          key={column.status}
          title={column.title}
          status={column.status}
          // その列のタスクだけを取り出し、並び順（sortOrder）の小さい順に並べる
          // （完了やドラッグで列を移ったタスクも、sortOrder どおりの位置に出るようにするため）
          tasks={tasks
            .filter((task) => task.status === column.status)
            .sort((a, b) => a.sortOrder - b.sortOrder)}
          onAdd={onAdd}
          onUpdate={onUpdate}
          onPatch={onPatch}
        />
      ))}
    </div>
  )
}

export default Board
