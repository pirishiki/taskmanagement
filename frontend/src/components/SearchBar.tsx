import { useState, type FormEvent } from 'react'

type Props = {
  onSearch: (keyword: string) => void
  // 検索中かどうか（最後に検索したキーワードが空でないか）
  searching: boolean
}

function SearchBar({ onSearch, searching }: Props) {
  const [keyword, setKeyword] = useState('')

  // 検索ボタンを押したとき、または Enter キーを押したときに呼ばれる
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める
    onSearch(keyword.trim())
  }

  // 「検索を解除」を押したとき：欄を空にして、全部のタスクを表示し直す
  function handleClear() {
    setKeyword('')
    onSearch('')
  }

  return (
    <form onSubmit={handleSubmit} className="mb-6 flex gap-2">
      <input
        type="text"
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        placeholder="タスク名で検索"
        className="w-72 rounded border border-gray-300 bg-white px-3 py-2 text-sm"
      />
      <button type="submit" className="rounded bg-[#0079bf] px-4 py-2 text-sm text-white hover:bg-[#026aa7]">
        検索
      </button>
      {/* 検索中だけ出す。空白で検索し直さなくても、全部の表示に戻せるようにするため */}
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
