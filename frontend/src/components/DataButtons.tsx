import { useRef, useState, type ChangeEvent } from 'react'

type Props = {
  // どちらも、終わる（成功・失敗とも）と解決する Promise を返す
  onExport: () => Promise<void>
  onImport: (file: File) => Promise<void>
}

// ボタンの見た目（送信中は薄くして、押せないことが分かるようにする）
const buttonClass =
  'rounded bg-white px-3 py-2 text-sm text-gray-700 shadow-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-white'

// 「⬇ 書き出す」「⬆ 読み込む」のボタン（タスクのデータのバックアップと、そこからの復元）
function DataButtons({ onExport, onImport }: Props) {
  // ファイルを選ぶ部品（<input type="file">）を指すメモ
  // この部品は見た目を変えにくいので画面には出さず、「⬆ 読み込む」が押されたら、プログラムから代わりにクリックする
  const fileInput = useRef<HTMLInputElement>(null)
  // 書き出し・読み込みの通信中。どちらかの最中は、どちらのボタンも押せない（読み込みと書き出しが重なると、中身が食いちがうため）
  const [busy, setBusy] = useState<'export' | 'import' | null>(null)
  // state は描き直しまで古いままなので、素早い 2 回目を防ぐために ref でも覚える
  const busyRef = useRef(false)

  // 通信中の印をつけて task を動かし、終わったら（成功・失敗とも）外す
  async function runBusy(kind: 'export' | 'import', task: () => Promise<void>) {
    if (busyRef.current) {
      return // すでに通信中。2 本目は送らない
    }
    busyRef.current = true
    setBusy(kind)
    try {
      await task()
    } finally {
      busyRef.current = false
      setBusy(null)
    }
  }

  // ファイルが選ばれたとき：確認のダイアログを出し、OK のときだけ App に読み込みを頼む
  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    // 選んだ値を空に戻す（同じファイルをもう一度選んだときも、この関数が呼ばれるようにするため）
    event.target.value = ''
    if (!file || busyRef.current) {
      return // ファイルを選ぶ画面で「キャンセル」を押した、または別の通信中
    }
    // 読み込むと今の列とタスクは全部消えるので、押し間違いを防ぐために確かめる（confirm は OK なら true）
    if (
      window.confirm(
        `「${file.name}」を読み込みます。今の列とタスクは全部消え、ファイルの列とタスクに置き換わります。よろしいですか？\n` +
          '（元に戻したいときのために、先に「⬇ 書き出す」で今の列とタスクを保存しておくと安心です）',
      )
    ) {
      void runBusy('import', () => onImport(file))
    }
  }

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={() => void runBusy('export', onExport)}
        disabled={busy !== null}
        className={buttonClass}
      >
        {busy === 'export' ? '書き出し中…' : '⬇ 書き出す'}
      </button>
      <button
        type="button"
        onClick={() => fileInput.current?.click()}
        disabled={busy !== null}
        className={buttonClass}
      >
        {busy === 'import' ? '読み込み中…' : '⬆ 読み込む'}
      </button>
      {/* accept：選ぶ画面で、JSON のファイルだけが出るようにする */}
      <input
        ref={fileInput}
        type="file"
        accept="application/json,.json"
        onChange={handleFileChange}
        className="hidden"
      />
    </div>
  )
}

export default DataButtons
