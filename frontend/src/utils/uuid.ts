// 番号札に使う UUID（バージョン 4）を作る
// crypto.randomUUID() は、安全な接続（HTTPS か http://localhost）でしか使えない。
// AWS 版は http://<IP> で開くので、なければ crypto.getRandomValues()（安全でない接続でも使える）で作る
export function createUuid(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  const bytes = crypto.getRandomValues(new Uint8Array(16))
  // UUID v4 の決まり：7 バイト目の上位 4 ビットを 0100（バージョン 4）、9 バイト目の上位 2 ビットを 10（8〜b）にする
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  // 16 進の文字 32 個にして、8-4-4-4-12 の形に区切る
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
