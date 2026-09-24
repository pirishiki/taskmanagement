import type { NewTask, Task } from '../types/task'
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
}

function Board({ tasks, onAdd }: Props) {
  return (
    <div className="flex items-start gap-4 overflow-x-auto">
      {columns.map((column) => (
        <Column
          key={column.status}
          title={column.title}
          status={column.status}
          tasks={tasks.filter((task) => task.status === column.status)}
          onAdd={onAdd}
        />
      ))}
    </div>
  )
}

export default Board
