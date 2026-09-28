import { useState, type FormEvent } from 'react'
import { noFilters, type DueFilter, type Task, type TaskFilters } from '../types/task'

// 優先度のチェックボックスの並び（左から高・中・低）
const priorityOptions: { value: Task['priority']; label: string }[] = [
  { value: 'high', label: '高' },
  { value: 'medium', label: '中' },
  { value: 'low', label: '低' },
]

// 期限のセレクトの選択肢（'' は「期限で絞らない」）
const dueOptions: { value: DueFilter; label: string }[] = [
  { value: '', label: '期限：すべて' },
  { value: 'overdue', label: '期限切れ' },
  { value: 'week', label: '7日以内' },
  { value: 'none', label: '期限なし' },
]

type Props = {
  onSearch: (filters: TaskFilters) => void
  // 検索・絞り込み中かどうか（最後に検索したときの条件が、1つでも選ばれていたか）
  searching: boolean
}

function SearchBar({ onSearch, searching }: Props) {
  // 入力中の条件。「検索」を押すまでは、App には伝えない
  const [keyword, setKeyword] = useState(noFilters.keyword)
  const [priorities, setPriorities] = useState<Task['priority'][]>(noFilters.priorities)
  const [due, setDue] = useState<DueFilter>(noFilters.due)

  // 優先度のチェックボックスを押したとき：チェックが付いたらリストに足し、外れたらリストから抜く
  function togglePriority(priority: Task['priority'], checked: boolean) {
    setPriorities((prev) => (checked ? [...prev, priority] : prev.filter((p) => p !== priority)))
  }

  // 検索ボタンを押したとき、または Enter キーを押したときに呼ばれる。3つの条件をまとめて伝える
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める
    onSearch({ keyword: keyword.trim(), priorities, due })
  }

  // 「検索を解除」を押したとき：全部の条件を空にして、全部のタスクを表示し直す
  function handleClear() {
    setKeyword(noFilters.keyword)
    setPriorities(noFilters.priorities)
    setDue(noFilters.due)
    onSearch(noFilters)
  }

  return (
    <form onSubmit={handleSubmit} className="mb-6 flex flex-wrap items-center gap-2">
      <input
        type="text"
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        placeholder="タスク名で検索"
        className="w-72 rounded border border-gray-300 bg-white px-3 py-2 text-sm"
      />
      {/* 優先度：何も付けなければ、優先度では絞らない */}
      <fieldset className="flex items-center gap-3 rounded border border-gray-300 bg-white px-3 py-2 text-sm">
        <legend className="sr-only">優先度</legend>
        <span className="text-gray-600">優先度：</span>
        {priorityOptions.map((option) => (
          <label key={option.value} className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={priorities.includes(option.value)}
              onChange={(event) => togglePriority(option.value, event.target.checked)}
            />
            {option.label}
          </label>
        ))}
      </fieldset>
      <select
        value={due}
        // セレクトの値は、dueOptions の value のどれかなので、DueFilter と決めてよい
        onChange={(event) => setDue(event.target.value as DueFilter)}
        aria-label="期限"
        className="rounded border border-gray-300 bg-white px-3 py-2 text-sm"
      >
        {dueOptions.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <button type="submit" className="rounded bg-[#0079bf] px-4 py-2 text-sm text-white hover:bg-[#026aa7]">
        検索
      </button>
      {/* 検索・絞り込み中だけ出す。条件を1つずつ外さなくても、全部の表示に戻せるようにするため */}
      {searching && (
        <button
          type="button"
          onClick={handleClear}
          className="rounded px-3 py-2 text-sm text-gray-600 hover:bg-white/60 hover:text-gray-800"
        >
          ✕ 検索を解除
        </button>
      )}
    </form>
  )
}

export default SearchBar
