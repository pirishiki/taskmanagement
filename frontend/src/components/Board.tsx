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
import type { BoardColumn } from '../types/column'
import type { NewTask, SortCriterion, Task, TaskPatch } from '../types/task'
import AddColumnForm from './AddColumnForm'
import Column, { droppableIdPrefix } from './Column'
import { TaskCardOverlay } from './TaskCard'

type Props = {
  // 並べる列（左から順）。App がバックエンドから取ってきたもの
  columns: BoardColumn[]
  tasks: Task[]
  onAdd: (task: NewTask) => void
  onUpdate: (id: number, task: NewTask) => void
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  onMove: (taskId: number, toColumnId: number, toIndex: number) => void
  onSort: (columnId: number, criterion: SortCriterion) => void
  onAddColumn: (name: string) => void
  onDeleteColumn: (columnId: number) => void
}

// その列のタスクだけを取り出し、並び順（sortOrder）の小さい順に並べる
// （完了やドラッグで列を移ったタスクも、sortOrder どおりの位置に出るようにするため）
function tasksIn(tasks: Task[], columnId: number) {
  return tasks.filter((task) => task.columnId === columnId).sort((a, b) => a.sortOrder - b.sortOrder)
}

function Board({
  columns,
  tasks,
  onAdd,
  onUpdate,
  onPatch,
  onDelete,
  onMove,
  onSort,
  onAddColumn,
  onDeleteColumn,
}: Props) {
  // 完了の列の番号（カードの ○ ボタンの移し先）。列の一覧をまだ取れていないときは null
  const doneColumnId = columns.find((column) => column.done)?.id ?? null

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

  // 落とした先（over）が、どの列かを調べて、列の番号を返す。列なら id が 'column-1' などの文字、カードなら数字
  function columnIdOf(list: Task[], overId: UniqueIdentifier) {
    if (typeof overId === 'string') {
      // 文字の id を持つのは、Column の useDroppable で作った列だけ。'column-' のあとの数字が列の番号
      return Number(overId.slice(droppableIdPrefix.length))
    }
    return list.find((task) => task.id === overId)?.columnId
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
      const toColumnId = columnIdOf(list, over.id)
      if (!moving || toColumnId === undefined || moving.columnId === toColumnId) {
        return list // 同じ列の中の入れ替えは、dnd-kit がカードをずらして見せてくれる
      }
      // カードの上なら、そのカードとすぐ上のカードの真ん中の番号にして、そのカードのすぐ上に入れる
      // （番号は小数なので、カードどうしの差が 1 より小さいこともある。決まった数を引くと、別のカードを追い越してしまう）
      // 列の空いた所なら、一番下に入れる
      const column = tasksIn(list, toColumnId)
      const overIndex = column.findIndex((task) => task.id === over.id)
      let sortOrder = Number.MAX_SAFE_INTEGER
      if (overIndex >= 0) {
        const overTask = column[overIndex]
        const aboveTask = column[overIndex - 1]
        sortOrder = aboveTask ? (aboveTask.sortOrder + overTask.sortOrder) / 2 : overTask.sortOrder - 1
      }
      return list.map((task) => (task.id === moving.id ? { ...task, columnId: toColumnId, sortOrder } : task))
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
    const toColumnId = columnIdOf(list, over.id)
    if (toColumnId === undefined) {
      return
    }

    // 仮の一覧では、つかんだカードはもう移動先の列に入っている
    const column = tasksIn(list, toColumnId)
    const activeIndex = column.findIndex((task) => task.id === taskId)
    const overIndex = column.findIndex((task) => task.id === over.id)
    // カードの上に落としたらそのカードの位置、列の空いた所に落としたら今の位置
    onMove(taskId, toColumnId, overIndex >= 0 ? overIndex : activeIndex)
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
      <div className="flex items-start gap-4 overflow-x-auto">
        {columns.map((column) => (
          <Column
            key={column.id}
            column={column}
            tasks={tasksIn(shown, column.id)}
            doneColumnId={doneColumnId}
            onAdd={onAdd}
            onUpdate={onUpdate}
            onPatch={onPatch}
            onDelete={onDelete}
            onSort={onSort}
            onDeleteColumn={onDeleteColumn}
          />
        ))}
        {/* 一番右に「＋ 列を追加」。足した列は、このボタンのすぐ左（右端の列）に入る */}
        <AddColumnForm onAdd={onAddColumn} />
      </div>
      {/* DragOverlay：列の枠とは関係なく、画面の一番手前にカードの分身を出して、マウスに付いてこさせる */}
      <DragOverlay>{activeTask && <TaskCardOverlay task={activeTask} />}</DragOverlay>
    </DndContext>
  )
}

export default Board
