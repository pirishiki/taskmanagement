import type { BoardColumn } from '../types/column'
import { jsonHeaders, request } from './taskApi'

// 列の API（/api/columns）を呼ぶ関数。失敗したときは、タスクの API と同じく ApiError を投げる

// 全部の列を、左から順に取得する
export async function fetchColumns(): Promise<BoardColumn[]> {
  const response = await request('/api/columns')
  return response.json()
}

// 列を右端に足す。作った列（id 付き）が返る
export async function createColumn(name: string): Promise<BoardColumn> {
  const response = await request('/api/columns', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({ name }),
  })
  return response.json()
}

// 列を消す。返ってくる中身はない（204）
// 基本の列・中にタスクがいる列は消せない（409 の ApiError になる。理由は error.message に入っている）
export async function deleteColumn(id: number): Promise<void> {
  await request(`/api/columns/${id}`, { method: 'DELETE' })
}
