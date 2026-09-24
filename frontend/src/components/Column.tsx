import type { NewTask, Task } from '../types/task'
import AddTaskForm from './AddTaskForm'
import TaskCard from './TaskCard'

type Props = {
  title: string
  status: Task['status']
  tasks: Task[]
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
}

function Column({ title, status, tasks, onAdd, onUpdate }: Props) {
  return (
    <section className="w-72 shrink-0 rounded-md bg-[#ebecf0] p-3">
      <h2 className="mb-3 font-bold text-gray-700">{title}</h2>
      <div className="flex flex-col gap-2">
        {tasks.map((task) => (
          <TaskCard key={task.id} task={task} onUpdate={onUpdate} />
        ))}
      </div>
      {/* フォームから届いた中身に、この列の status を書き足して上に伝える */}
      <AddTaskForm onAdd={(task) => onAdd({ ...task, status })} />
    </section>
  )
}

export default Column
