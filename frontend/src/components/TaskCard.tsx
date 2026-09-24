import type { Task } from '../types/task'

// 優先度ごとの表示名と色（試作版 style.css と同じ色）
const priorityLabels: Record<Task['priority'], string> = {
  high: '高',
  medium: '中',
  low: '低',
}

const priorityColors: Record<Task['priority'], string> = {
  high: 'bg-[#eb5a46]',
  medium: 'bg-[#ff9f1a]',
  low: 'bg-[#61bd4f]',
}

type Props = {
  task: Task
}

function TaskCard({ task }: Props) {
  return (
    <div className="rounded bg-white p-3 shadow-sm">
      <p className="text-sm text-gray-800">{task.text}</p>
      <div className="mt-2 flex items-center gap-3 text-xs text-gray-500">
        <span className="flex items-center gap-1">
          <span className={`inline-block h-2.5 w-2.5 rounded-full ${priorityColors[task.priority]}`} />
          {priorityLabels[task.priority]}
        </span>
        {task.dueDate && <span>期限: {task.dueDate}</span>}
      </div>
    </div>
  )
}

export default TaskCard
