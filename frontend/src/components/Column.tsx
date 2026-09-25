import { useDroppable } from '@dnd-kit/core'
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import type { NewTask, SortCriterion, Task, TaskPatch } from '../types/task'
import AddTaskForm from './AddTaskForm'
import TaskCard from './TaskCard'

type Props = {
  title: string
  status: Task['status']
  tasks: Task[]
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  onSort: (status: Task['status'], criterion: SortCriterion) => void
  canDrag: boolean
}

function Column({ title, status, tasks, onAdd, onUpdate, onPatch, onDelete, onSort, canDrag }: Props) {
  // ドラッグ＆ドロップ：この列を「置き場所」にする。id には列の status（'todo' など）を使う
  // カードが1枚もない列でも、ここに落とせるようにするため
  const { setNodeRef } = useDroppable({ id: status })

  return (
    <section className="w-72 shrink-0 rounded-md bg-[#ebecf0] p-3">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="font-bold text-gray-700">{title}</h2>
        {/* 並び替えセレクト：選んだ時点で一度だけ並べ直す。value はいつも ''（「並び替え」）なので、選んだあとは表示が元に戻る */}
        {/* 検索中は、見えていないタスクと並び順がずれるのを防ぐため、ドラッグと同じく使えなくする */}
        <select
          value=""
          onChange={(event) => onSort(status, event.target.value as SortCriterion)}
          disabled={!canDrag}
          title={canDrag ? undefined : '検索中は並び替えできません'}
          className="rounded bg-white px-1 py-0.5 text-xs text-gray-600 disabled:opacity-50"
        >
          <option value="" disabled>
            並び替え
          </option>
          <option value="priority">優先度順</option>
          <option value="dueDate">期限が近い順</option>
        </select>
      </div>
      {/* SortableContext：この中のカードは、上下に並び替えられる。items には並んでいる順の id を渡す */}
      <SortableContext items={tasks.map((task) => task.id)} strategy={verticalListSortingStrategy}>
        {/* min-h：カードがない列でも、落とせる広さを残す */}
        <div ref={setNodeRef} className="flex min-h-8 flex-col gap-2">
          {tasks.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              onUpdate={onUpdate}
              onPatch={onPatch}
              onDelete={onDelete}
              canDrag={canDrag}
            />
          ))}
        </div>
      </SortableContext>
      {/* フォームから届いた中身に、この列の status を書き足して上に伝える */}
      <AddTaskForm onAdd={(task) => onAdd({ ...task, status })} />
    </section>
  )
}

export default Column
