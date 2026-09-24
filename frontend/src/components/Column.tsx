import { useDroppable } from '@dnd-kit/core'
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import type { NewTask, Task, TaskPatch } from '../types/task'
import AddTaskForm from './AddTaskForm'
import TaskCard from './TaskCard'

type Props = {
  title: string
  status: Task['status']
  tasks: Task[]
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
}

function Column({ title, status, tasks, onAdd, onUpdate, onPatch }: Props) {
  // ドラッグ＆ドロップ：この列を「置き場所」にする。id には列の status（'todo' など）を使う
  // カードが1枚もない列でも、ここに落とせるようにするため
  const { setNodeRef } = useDroppable({ id: status })

  return (
    <section className="w-72 shrink-0 rounded-md bg-[#ebecf0] p-3">
      <h2 className="mb-3 font-bold text-gray-700">{title}</h2>
      {/* SortableContext：この中のカードは、上下に並び替えられる。items には並んでいる順の id を渡す */}
      <SortableContext items={tasks.map((task) => task.id)} strategy={verticalListSortingStrategy}>
        {/* min-h：カードがない列でも、落とせる広さを残す */}
        <div ref={setNodeRef} className="flex min-h-8 flex-col gap-2">
          {tasks.map((task) => (
            <TaskCard key={task.id} task={task} onUpdate={onUpdate} onPatch={onPatch} />
          ))}
        </div>
      </SortableContext>
      {/* フォームから届いた中身に、この列の status を書き足して上に伝える */}
      <AddTaskForm onAdd={(task) => onAdd({ ...task, status })} />
    </section>
  )
}

export default Column
