import { useState, type FormEvent, type KeyboardEvent } from 'react'

type Props = {
  // 入力された列の名前（前後の空白は取ってある）を上に伝える
  onAdd: (name: string) => void
}

// ボードの右端に出す「＋ 列を追加」。カードの追加（AddTaskForm）と同じく、ふだんはボタンだけを出す
function AddColumnForm({ onAdd }: Props) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')

  // ✕ ボタン、または Esc キーでフォームを閉じる（入力途中の内容は捨てる）
  function close() {
    setName('')
    setOpen(false)
  }

  // 追加ボタンを押したとき、または名前の欄で Enter キーを押したときに呼ばれる
  function handleSubmit(event: FormEvent) {
    event.preventDefault() // ページの再読み込みを止める

    const trimmed = name.trim()
    if (trimmed === '') {
      return // 空欄や空白だけのときは何もしない（カードの追加と同じ）
    }

    onAdd(trimmed)

    // 続けて列を足せるように、フォームは開いたまま欄だけを空にする
    setName('')
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      close()
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="w-72 shrink-0 rounded-md bg-white/50 px-3 py-2.5 text-left text-sm text-gray-700 hover:bg-white/80"
      >
        ＋ 列を追加
      </button>
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex w-72 shrink-0 flex-col gap-2 rounded-md bg-[#ebecf0] p-3">
      <input
        type="text"
        value={name}
        onChange={(event) => setName(event.target.value)}
        onKeyDown={handleKeyDown}
        autoFocus
        // バックエンドと同じく 255 文字まで（DB の列が 255 文字までのため）
        maxLength={255}
        placeholder="列の名前を入力…"
        className="rounded bg-white px-3 py-2 text-sm shadow-sm outline-none"
      />
      <div className="flex items-center gap-2">
        <button type="submit" className="rounded bg-[#0079bf] px-3 py-1.5 text-sm text-white hover:bg-[#026aa7]">
          列を追加
        </button>
        <button
          type="button"
          onClick={close}
          aria-label="閉じる"
          className="rounded px-2 py-1 text-lg leading-none text-gray-500 hover:bg-gray-300/60 hover:text-gray-800"
        >
          ✕
        </button>
      </div>
    </form>
  )
}

export default AddColumnForm
