// ボードの番号が変わった（DB が作り直された）ときに、画面の上に出す黄色の帯（docs/同時編集の設計書.md の 6.1 節）
// 一度出したら、ページを読み込み直すまで消さない（この画面の内容は、もう DB と合わないため）
// 「読み込み直す」は、ページを再読み込みする（入力中の内容は消えるので、帯の文でそう伝える）
function BoardChangedBanner() {
  return (
    <div role="alert" className="mb-4 flex flex-wrap items-center gap-3 rounded bg-yellow-200 p-3 text-yellow-900">
      <p className="min-w-0 flex-1">
        データベースが作り直されたため、この画面の内容は古くなっています。いまの内容を見るには「読み込み直す」を押してください（この画面で入力中の内容は消えます）。
      </p>
      <button
        type="button"
        onClick={() => window.location.reload()}
        className="shrink-0 rounded bg-yellow-700 px-4 py-1.5 text-sm text-white hover:bg-yellow-800"
      >
        読み込み直す
      </button>
    </div>
  )
}

export default BoardChangedBanner
