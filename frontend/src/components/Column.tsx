import { useDroppable } from '@dnd-kit/core'
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import type { BoardColumn } from '../types/column'
import type { NewTask, SortCriterion, Task, TaskPatch } from '../types/task'
import AddTaskForm from './AddTaskForm'
import TaskCard from './TaskCard'

// 並び替えの選択肢。画面に出す <option> も、選ばれた値の確かめも、この一覧から作る
const sortOptions: { value: SortCriterion; label: string }[] = [
  { value: 'priority', label: '優先度順' },
  { value: 'dueDate', label: '期限が近い順' },
]

// ドラッグ＆ドロップで使う、列の「置き場所」の id
// カードの id（タスクの番号）と列の番号は、どちらも数字なのでぶつかることがある（タスク1 と 列1 など）
// そこで、列のほうは 'column-1' のような文字にして、カードと見分けられるようにする
export const droppableIdPrefix = 'column-'

type Props = {
  column: BoardColumn
  tasks: Task[]
  // 完了の列の番号（カードの ○ ボタンで使う）
  doneColumnId: number | null
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  onSort: (columnId: number, criterion: SortCriterion) => void
  onDeleteColumn: (columnId: number) => void
}

function Column({ column, tasks, doneColumnId, onAdd, onUpdate, onPatch, onDelete, onSort, onDeleteColumn }: Props) {
  // 列の × を押したとき：押し間違いで消えないよう、ブラウザの確認ダイアログを出す（カードの × と同じ）
  // タスクが入っている列は、サーバーが断る（409）。理由は App が画面の上に出す
  // （検索・絞り込み中は見えていないタスクもあるので、画面のカードの枚数では決めず、サーバーに確かめてもらう）
  function handleDeleteClick() {
    if (window.confirm(`列「${column.name}」を削除しますか？`)) {
      onDeleteColumn(column.id)
    }
  }

  // ドラッグ＆ドロップ：この列を「置き場所」にする。id は 'column-1' のような文字（上の droppableIdPrefix を参照）
  // カードが1枚もない列でも、ここに落とせるようにするため
  const { setNodeRef } = useDroppable({ id: `${droppableIdPrefix}${column.id}` })

  return (
    // group/column：中の部品が「この列にマウスが乗っているか・フォーカスがあるか」で見た目を変えられるようにする目印
    // （カードにも group が付いているので、名前（/column）を付けて区別する）
    <section className="group/column w-72 shrink-0 rounded-md bg-[#ebecf0] p-3">
      <div className="mb-3 flex items-center justify-between gap-2">
        {/* min-w-0 と break-words：長い名前でも、列の幅からはみ出さずに折り返す */}
        <h2 className="min-w-0 flex-1 font-bold break-words text-gray-700">{column.name}</h2>
        {/* 並び替えセレクト：選んだ時点で一度だけ並べ直す。value はいつも ''（「並び替え」）なので、選んだあとは表示が元に戻る */}
        {/* 検索・絞り込み中は、見えているカードだけを並べ直す（見えていないカードの位置は変わらない） */}
        <select
          value=""
          onChange={(event) => {
            // 選ばれた値を選択肢の一覧から探す。見つかれば、その値は必ず 'priority' | 'dueDate' のどちらか
            const selected = sortOptions.find((option) => option.value === event.target.value)
            if (selected) {
              onSort(column.id, selected.value)
            }
          }}
          className="rounded bg-white px-1 py-0.5 text-xs text-gray-600"
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
        {/* ×：列を削除する。完了の列は消せない（○ ボタンの移し先がなくなる）ので、ボタンを出さない */}
        {/* 列にマウスが乗っているとき、または列の中にキーボードのフォーカスがあるときだけ見せる（ホバー表示） */}
        {/* カードの ✎・× はいつも出すが、列の削除はめったに使わず、見出しをすっきりさせたいため（要件定義書 6.2） */}
        {/* opacity（透明度）で隠すので、ボタンの場所は空いたまま。見せたり隠したりしても見出しの並びがずれない */}
        {!column.done && (
          <button
            type="button"
            onClick={handleDeleteClick}
            aria-label={`列「${column.name}」を削除`}
            title="列を削除"
            className="shrink-0 rounded px-1 text-gray-400 opacity-0 group-focus-within/column:opacity-100 group-hover/column:opacity-100 hover:bg-gray-300/60 hover:text-[#eb5a46] focus-visible:opacity-100"
          >
            ×
          </button>
        )}
      </div>
      {/* SortableContext：この中のカードは、上下に並び替えられる。items には並んでいる順の id を渡す */}
      <SortableContext items={tasks.map((task) => task.id)} strategy={verticalListSortingStrategy}>
        {/* min-h：カードがない列でも、落とせる広さを残す */}
        <div ref={setNodeRef} className="flex min-h-8 flex-col gap-2">
          {tasks.map((task) => (
            <TaskCard
              key={task.id}
              task={task}
              doneColumnId={doneColumnId}
              onUpdate={onUpdate}
              onPatch={onPatch}
              onDelete={onDelete}
            />
          ))}
        </div>
      </SortableContext>
      {/* フォームから届いた中身に、この列の番号を書き足して上に伝える */}
      <AddTaskForm onAdd={(task) => onAdd({ ...task, columnId: column.id })} />
    </section>
  )
}

export default Column
