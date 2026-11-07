import type { BoardColumn } from '../types/column'
import { ApiError, jsonHeaders, request } from './taskApi'

// 列の API（/api/columns）を呼ぶ関数。失敗したときは、タスクの API と同じく ApiError を投げる

// 全部の列を、左から順に取得する
export async function fetchColumns(): Promise<BoardColumn[]> {
  const response = await request('/api/columns')
  return response.json()
}

// 列を右端に足す。作った列（id 付き）が返る
// idempotencyKey は番号札（UUID）。タスクの追加 createTask と同じ決まり
export async function createColumn(name: string, idempotencyKey: string): Promise<BoardColumn> {
  const response = await request('/api/columns', {
    method: 'POST',
    headers: { ...jsonHeaders, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ name }),
  })
  return response.json()
}

// 列を消す（DELETE /api/columns/{id}?version=…）。返ってくる中身はない（204）
// 基本の列・中にタスクがいる列は消せない（409 の ApiError になる。理由は error.message に入っている）
// version は、画面が見ていた列の版。ほかの画面で名前が変わっていれば 409（VERSION_CONFLICT。current に列のいまの状態）
// 404（その列はもうない）も成功として扱う。別のタブなどで先に消されていても、「消したい」という目的は果たせているため
// （タスクの削除 deleteTask と同じ考え方）
export async function deleteColumn(id: number, version: number): Promise<void> {
  try {
    await request(`/api/columns/${id}?version=${version}`, { method: 'DELETE' })
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return
    }
    throw error
  }
}
