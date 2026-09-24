import type { Task } from '../types/task'
import TaskCard from './TaskCard'

type Props = {
  title: string
  tasks: Task[]
}

function Column({ title, tasks }: Props) {
  return (
    <section className="w-72 shrink-0 rounded-md bg-[#ebecf0] p-3">
      <h2 className="mb-3 font-bold text-gray-700">{title}</h2>
      <div className="flex flex-col gap-2">
        {tasks.map((task) => (
          <TaskCard key={task.id} task={task} />
        ))}
      </div>
    </section>
  )
}

export default Column
