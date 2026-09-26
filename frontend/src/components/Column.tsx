import { useDroppable } from '@dnd-kit/core'
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import type { NewTask, SortCriterion, Task, TaskPatch } from '../types/task'
import AddTaskForm from './AddTaskForm'
import TaskCard from './TaskCard'

// 並び替えの選択肢。画面に出す <option> も、選ばれた値の確かめも、この一覧から作る
const sortOptions: { value: SortCriterion; label: string }[] = [
  { value: 'priority', label: '優先度順' },
  { value: 'dueDate', label: '期限が近い順' },
]

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
          onChange={(event) => {
            // 選ばれた値を選択肢の一覧から探す。見つかれば、その値は必ず 'priority' | 'dueDate' のどちらか
            const selected = sortOptions.find((option) => option.value === event.target.value)
            if (selected) {
              onSort(status, selected.value)
            }
          }}
          disabled={!canDrag}
          title={canDrag ? undefined : '検索中は並び替えできません'}
          className="rounded bg-white px-1 py-0.5 text-xs text-gray-600 disabled:opacity-50"
        >
          <option value="" disabled>
            並び替え
          </option>
          {sortOptions.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
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
