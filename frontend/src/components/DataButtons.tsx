import { useRef, type ChangeEvent } from 'react'

type Props = {
  onExport: () => void
  onImport: (file: File) => void
}

// 「⬇ 書き出す」「⬆ 読み込む」のボタン（タスクのデータのバックアップと、そこからの復元）
function DataButtons({ onExport, onImport }: Props) {
  // ファイルを選ぶ部品（<input type="file">）を指すメモ
  // この部品は見た目を変えにくいので画面には出さず、「⬆ 読み込む」が押されたら、プログラムから代わりにクリックする
  const fileInput = useRef<HTMLInputElement>(null)

  // ファイルが選ばれたとき：確認のダイアログを出し、OK のときだけ App に読み込みを頼む
  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    // 選んだ値を空に戻す（同じファイルをもう一度選んだときも、この関数が呼ばれるようにするため）
    event.target.value = ''
    if (!file) {
      return // ファイルを選ぶ画面で「キャンセル」を押した
    }
    // 読み込むと今の列とタスクは全部消えるので、押し間違いを防ぐために確かめる（confirm は OK なら true）
    if (
      window.confirm(
        `「${file.name}」を読み込みます。今の列とタスクは全部消え、ファイルの列とタスクに置き換わります。よろしいですか？\n` +
          '（元に戻したいときのために、先に「⬇ 書き出す」で今の列とタスクを保存しておくと安心です）',
      )
    ) {
      onImport(file)
    }
  }

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={onExport}
        className="rounded bg-white px-3 py-2 text-sm text-gray-700 shadow-sm hover:bg-gray-50"
      >
        ⬇ 書き出す
      </button>
      <button
        type="button"
        onClick={() => fileInput.current?.click()}
        className="rounded bg-white px-3 py-2 text-sm text-gray-700 shadow-sm hover:bg-gray-50"
      >
        ⬆ 読み込む
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
