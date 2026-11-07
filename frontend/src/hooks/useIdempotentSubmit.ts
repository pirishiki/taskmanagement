import { useRef, useState } from 'react'
import type { AddResult } from '../types/task'

// 追加フォーム（タスク・列）の「番号札」と「送信中」を管理する。二重送信の設計書（docs/二重送信の設計書.md）の画面側の決まり
// ・追加のたびに番号札（crypto.randomUUID()）を作る。同じ内容で送り直すときは同じ札、内容が変わったら新しい札
// ・成功したら、次の追加用に新しい札にする
// ・送信中は sending が true。2 回目の送信（ボタン・Enter）は無視する
// send：実際に送る関数。結果（AddResult）を返す
export function useIdempotentSubmit<T>(send: (value: T, idempotencyKey: string) => Promise<AddResult>) {
  const [sending, setSending] = useState(false)
  // 今の番号札と、その札で送った内容（signature）。内容が同じかどうかの比べに使う
  const keyRef = useRef<{ key: string; signature: string } | null>(null)
  // state は描き直しまで古いままなので、2 回続けて素早く呼ばれても防げるように、ref でも送信中を覚える
  const sendingRef = useRef(false)

  // value を送る。signature は、value の内容を表す文字列（同じ内容なら同じ文字列）
  // 送信中で無視したときは null、そうでなければ送った結果を返す
  async function submit(value: T, signature: string): Promise<AddResult | null> {
    if (sendingRef.current) {
      return null
    }
    // 札がない、または内容が前に送ったものとちがうときは、新しい札にする
    if (keyRef.current === null || keyRef.current.signature !== signature) {
      keyRef.current = { key: crypto.randomUUID(), signature }
    }

    sendingRef.current = true
    setSending(true)
    try {
      const result = await send(value, keyRef.current.key)
      // 成功した（次の追加は別のもの）か、同じ札では必ず失敗する場合は、札を捨てる
      if (result !== 'failed') {
        keyRef.current = null
      }
      return result
    } finally {
      sendingRef.current = false
      setSending(false)
    }
  }

  return { sending, submit }
}
