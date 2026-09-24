import { useState, type FormEvent } from 'react'

type Props = {
  onSearch: (keyword: string) => void
}

function SearchBar({ onSearch }: Props) {
  const [keyword, setKeyword] = useState('')

  // 検索ボタンを押したとき、または Enter キーを押したときに呼ばれる
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める
    onSearch(keyword.trim())
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
    </form>
  )
}

export default SearchBar
