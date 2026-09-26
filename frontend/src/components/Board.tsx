import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type UniqueIdentifier,
} from '@dnd-kit/core'
import { sortableKeyboardCoordinates } from '@dnd-kit/sortable'
import { useState } from 'react'
import type { NewTask, SortCriterion, Task, TaskPatch } from '../types/task'
import Column from './Column'
import { TaskCardOverlay } from './TaskCard'

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
  onDelete: (id: number) => void
  onMove: (taskId: number, toStatus: Task['status'], toIndex: number) => void
  onSort: (status: Task['status'], criterion: SortCriterion) => void
  canDrag: boolean
}

// その列のタスクだけを取り出し、並び順（sortOrder）の小さい順に並べる
// （完了やドラッグで列を移ったタスクも、sortOrder どおりの位置に出るようにするため）
function tasksIn(tasks: Task[], status: Task['status']) {
  return tasks.filter((task) => task.status === status).sort((a, b) => a.sortOrder - b.sortOrder)
}

function Board({ tasks, onAdd, onUpdate, onPatch, onDelete, onMove, onSort, canDrag }: Props) {
  // ドラッグ中だけ使う「仮のタスク一覧」。別の列の上に来たら、ここでカードを仮に移す
  // （移動先の列のカードが場所を空けて、どこに入るかが見えるようにするため）
  // null のときはドラッグしていないので、本物の tasks をそのまま表示する
  const [preview, setPreview] = useState<Task[] | null>(null)
  const [activeTask, setActiveTask] = useState<Task | null>(null)
  const shown = preview ?? tasks

  // どの操作でドラッグを始めるか
  // マウス：5px 以上動かしたらドラッグ開始（○ や ✎ をクリックしただけでドラッグにならないようにするため）
  // キーボード：カードを選んで Space でつかみ、矢印キーで動かす
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  // 落とした先（over）が、どの列かを調べる。列なら id が 'todo' などの文字、カードなら数字
  function statusOf(list: Task[], overId: UniqueIdentifier) {
    if (typeof overId === 'string') {
      // 文字の id を持つのは、Column の useDroppable({ id: status }) で作った列だけなので、status と決めてよい
      // （dnd-kit の id は string | number の型しか持てないため、ここは as で決めつける）
      return overId as Task['status']
    }
    return list.find((task) => task.id === overId)?.status
  }

  // つかんだとき：仮の一覧を作り、マウスに付いてくる分身のために、つかんだタスクを覚える
  function handleDragStart({ active }: DragStartEvent) {
    setPreview(tasks)
    setActiveTask(tasks.find((task) => task.id === active.id) ?? null)
  }

  // ドラッグ中に、別の列の上に来たとき：仮の一覧の中で、カードをその列へ移す
  function handleDragOver({ active, over }: DragOverEvent) {
    if (!over) {
      return
    }
    setPreview((prev) => {
      const list = prev ?? tasks
      const moving = list.find((task) => task.id === active.id)
      const toStatus = statusOf(list, over.id)
      if (!moving || !toStatus || moving.status === toStatus) {
        return list // 同じ列の中の入れ替えは、dnd-kit がカードをずらして見せてくれる
      }
      // カードの上なら、そのカードのすぐ上に入れる（sortOrder を 0.5 小さくする）。列の空いた所なら、一番下に入れる
      const overTask = list.find((task) => task.id === over.id)
      const sortOrder = overTask ? overTask.sortOrder - 0.5 : Number.MAX_SAFE_INTEGER
      return list.map((task) => (task.id === moving.id ? { ...task, status: toStatus, sortOrder } : task))
    })
  }

  // 落としたとき：仮の一覧を見て、最終的な列と位置を決め、App に伝える
  function handleDragEnd({ active, over }: DragEndEvent) {
    const list = shown
    setPreview(null)
    setActiveTask(null)
    if (!over) {
      return // ボードの外に落としたときは何もしない
    }
    const taskId = Number(active.id)
    const toStatus = statusOf(list, over.id)
    if (!toStatus) {
      return
    }

    // 仮の一覧では、つかんだカードはもう移動先の列に入っている
    const column = tasksIn(list, toStatus)
    const activeIndex = column.findIndex((task) => task.id === taskId)
    const overIndex = column.findIndex((task) => task.id === over.id)
    // カードの上に落としたらそのカードの位置、列の空いた所に落としたら今の位置
    onMove(taskId, toStatus, overIndex >= 0 ? overIndex : activeIndex)
  }

  // Esc キーなどでドラッグを取りやめたとき：仮の一覧を捨てて、元に戻す
  function handleDragCancel() {
    setPreview(null)
    setActiveTask(null)
  }

  return (
    // DndContext：この中がドラッグできる範囲。closestCorners は、落とした先を「一番近い角」で決める方法（列をまたぐときに向いている）
    <DndContext
      sensors={sensors}
      collisionDetection={closestCorners}
      onDragStart={handleDragStart}
      onDragOver={handleDragOver}
      onDragEnd={handleDragEnd}
      onDragCancel={handleDragCancel}
    >
      {!canDrag && <p className="mb-3 text-sm text-red-600">検索中は、カードの並び替えはできません。</p>}
      <div className="flex items-start gap-4 overflow-x-auto">
        {columns.map((column) => (
          <Column
            key={column.status}
            title={column.title}
            status={column.status}
            tasks={tasksIn(shown, column.status)}
            onAdd={onAdd}
            onUpdate={onUpdate}
            onPatch={onPatch}
            onDelete={onDelete}
            onSort={onSort}
            canDrag={canDrag}
          />
        ))}
      </div>
      {/* DragOverlay：列の枠とは関係なく、画面の一番手前にカードの分身を出して、マウスに付いてこさせる */}
      <DragOverlay>{activeTask && <TaskCardOverlay task={activeTask} />}</DragOverlay>
    </DndContext>
  )
}

export default Board
