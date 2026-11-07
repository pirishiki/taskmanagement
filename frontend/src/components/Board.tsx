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
import { useCallback, useState } from 'react'
import type { BoardColumn } from '../types/column'
import type { AddResult, NewTask, SortCriterion, Task, TaskContent, TaskPatch, UpdateResult } from '../types/task'
import AddColumnForm from './AddColumnForm'
import Column, { droppableIdPrefix } from './Column'
import { TaskCardOverlay } from './TaskCard'

type Props = {
  // 並べる列（左から順）。App がバックエンドから取ってきたもの
  columns: BoardColumn[]
  tasks: Task[]
  // 書き込み中のタスク・列の番号（返事が来るまで操作できない。docs/同時編集の設計書.md の 6.1 節）
  busyTaskIds: ReadonlySet<number>
  busyColumnIds: ReadonlySet<number>
  onAdd: (task: NewTask, idempotencyKey: string) => Promise<AddResult>
  onUpdate: (id: number, task: TaskContent, version: number) => Promise<UpdateResult>
  onPatch: (id: number, patch: TaskPatch) => void
  onDelete: (id: number) => void
  onMove: (taskId: number, toColumnId: number, toIndex: number) => void
  onSort: (columnId: number, criterion: SortCriterion) => void
  onAddColumn: (name: string, idempotencyKey: string) => Promise<AddResult>
  onDeleteColumn: (columnId: number) => void
}

// その列のタスクだけを取り出し、並び順（sortOrder）の小さい順に並べる
// （完了やドラッグで列を移ったタスクも、sortOrder どおりの位置に出るようにするため）
function tasksIn(tasks: Task[], columnId: number) {
  return tasks.filter((task) => task.columnId === columnId).sort((a, b) => a.sortOrder - b.sortOrder)
}

// 編集フォームを開いているカードを、開いたときの列・位置に留めた一覧を作る
// 編集中に、カードの中身が「いまの内容」（409 の current）や取り直しの結果に入れ替わって、別の列に移ったり、一覧から外れたりすると、
// カードの部品が作り直されて、編集フォームと入力が黙って消えてしまうため（入力を残す、の決まり。設計書 6.2 節）
// 中身（タスク名・版など）は新しいものを見せ、列と位置だけを留める。一覧から外れていたら、開いたときのカードを残す
// 編集を閉じると留めるのをやめ、本当の列・位置に出る（一覧になければ消える）
function keepEditingInPlace(tasks: Task[], editing: Task | null): Task[] {
  if (editing === null) {
    return tasks
  }
  if (!tasks.some((task) => task.id === editing.id)) {
    return [...tasks, editing]
  }
  return tasks.map((task) =>
    task.id === editing.id ? { ...task, columnId: editing.columnId, sortOrder: editing.sortOrder } : task,
  )
}

// 編集フォームを開いているカードの列（開いたときの姿と、左から何番目だったか）を、列の一覧に残す
// 編集中に、取り直しでその列がなくなる（ほかの画面で空の列が消された）と、列ごとカードの部品が消え、編集フォームと入力が
// 黙って消えてしまうため。編集を閉じるまで、開いたときの列を同じ位置に描き続ける（閉じると、本当の列の一覧に戻る）
function keepEditingColumn(
  columns: BoardColumn[],
  editingColumn: { column: BoardColumn; index: number } | null,
): BoardColumn[] {
  if (editingColumn === null || columns.some((column) => column.id === editingColumn.column.id)) {
    return columns
  }
  const index = Math.min(editingColumn.index, columns.length)
  return [...columns.slice(0, index), editingColumn.column, ...columns.slice(index)]
}

function Board({
  columns,
  tasks,
  busyTaskIds,
  busyColumnIds,
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
  // 編集フォームを開いているカード（開いたときの姿）。開いていなければ null
  const [editing, setEditing] = useState<Task | null>(null)
  // そのカードがいた列（開いたときの姿と位置）。開いていなければ null
  const [editingColumn, setEditingColumn] = useState<{ column: BoardColumn; index: number } | null>(null)
  // 編集が終わったときに呼ぶ。useCallback で同じ関数を使い回す（カードの useEffect の依存に入るので、毎回作り直すと、
  // 描き直しのたびに「終わった」が呼ばれてしまうため）
  const endEditing = useCallback(() => {
    setEditing(null)
    setEditingColumn(null)
  }, [])
  // 編集を始めたとき：カードと、そのカードがいる列（と左から何番目か）を控える
  function startEditing(task: Task) {
    const index = columns.findIndex((column) => column.id === task.columnId)
    setEditing(task)
    setEditingColumn(index >= 0 ? { column: columns[index], index } : null)
  }
  const placed = keepEditingInPlace(tasks, editing)
  const shownColumns = keepEditingColumn(columns, editingColumn)
  const shown = preview ?? placed

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
    setPreview(placed)
    setActiveTask(placed.find((task) => task.id === active.id) ?? null)
  }

  // ドラッグ中に、別の列の上に来たとき：仮の一覧の中で、カードをその列へ移す
  function handleDragOver({ active, over }: DragOverEvent) {
    if (!over) {
      return
    }
    setPreview((prev) => {
      const list = prev ?? placed
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
        {/* 編集中のカードの列が、取り直しでなくなっていても、閉じるまで描く（keepEditingColumn） */}
        {shownColumns.map((column) => (
          <Column
            key={column.id}
            column={column}
            tasks={tasksIn(shown, column.id)}
            doneColumnId={doneColumnId}
            busyTaskIds={busyTaskIds}
            deleting={busyColumnIds.has(column.id)}
            onAdd={onAdd}
            onUpdate={onUpdate}
            onPatch={onPatch}
            onDelete={onDelete}
            onSort={onSort}
            onDeleteColumn={onDeleteColumn}
            onEditStart={startEditing}
            onEditEnd={endEditing}
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
