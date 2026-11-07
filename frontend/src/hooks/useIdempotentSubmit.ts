import { useRef, useState } from 'react'
import type { AddResult } from '../types/task'
import { createUuid } from '../utils/uuid'

// 追加フォーム（タスク・列）の「番号札」と「送信中」を管理する。二重送信の設計書（docs/二重送信の設計書.md）の画面側の決まり
// ・追加のたびに番号札（UUID）を作る。同じ内容で送り直すときは同じ札、内容が変わったら新しい札
// ・成功したら、次の追加用に新しい札にする
// ・送信中は sending が true。2 回目の送信（ボタン・Enter）は無視する
// ・札が作れない、送る処理が例外を投げた、というときは、submitError に理由を入れる（フォームが赤く出す。押しても何も起きない、にしない）
//   submitError は clearError で消す（入力が変わったとき、フォームを閉じる・開くとき）
// send：実際に送る関数。結果（AddResult）を返す
export function useIdempotentSubmit<T>(send: (value: T, idempotencyKey: string) => Promise<AddResult>) {
  const [sending, setSending] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  // 今の番号札と、その札で送った内容（signature）。内容が同じかどうかの比べに使う
  const keyRef = useRef<{ key: string; signature: string } | null>(null)
  // state は描き直しまで古いままなので、2 回続けて素早く呼ばれても防げるように、ref でも送信中を覚える
  const sendingRef = useRef(false)

  function clearError() {
    setSubmitError(null)
  }

  // value を送る。signature は、value の内容を表す文字列（同じ内容なら同じ文字列）
  // 送信中で無視したときは null、そうでなければ送った結果を返す（例外は投げない）
  async function submit(value: T, signature: string): Promise<AddResult | null> {
    if (sendingRef.current) {
      return null
    }
    sendingRef.current = true
    setSending(true)
    setSubmitError(null)
    try {
      // 札がない、または内容が前に送ったものとちがうときは、新しい札にする（札の生成の失敗だけ、専用のメッセージにする）
      if (keyRef.current === null || keyRef.current.signature !== signature) {
        try {
          keyRef.current = { key: createUuid(), signature }
        } catch {
          keyRef.current = null
          setSubmitError('番号札を作れませんでした。ブラウザを開き直してから、もう一度お試しください。')
          return 'failed'
        }
      }
      try {
        const result = await send(value, keyRef.current.key)
        // 成功した（次の追加は別のもの）か、同じ札では必ず失敗する場合は、札を捨てる
        if (result !== 'failed') {
          keyRef.current = null
        }
        return result
      } catch {
        // 送る処理が想定外の例外を投げた。入力は残す。送れたかどうか分からないので、札は残して同じ札で送り直せるようにする
        setSubmitError('追加できませんでした。もう一度お試しください。')
        return 'failed'
      }
    } finally {
      sendingRef.current = false
      setSending(false)
    }
  }

  return { sending, submit, submitError, clearError }
}
