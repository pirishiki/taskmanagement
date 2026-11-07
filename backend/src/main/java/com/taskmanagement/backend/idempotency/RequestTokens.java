package com.taskmanagement.backend.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// 番号札（ヘッダー Idempotency-Key）の係。表 request_tokens（V5）に、札と中身の指紋と、作ったものの番号を記録する
// 決まりは docs/二重送信の設計書.md の 2 節。使うのは、タスクの追加（TaskService）と列の追加（BoardColumnService）
//
// たとえ：お店の整理券。同じ整理券で 2 回来た人には、2 つ目の品物を作らず、1 回目に渡した品物を見せる
// 流れ（追加の Service の中。追加と同じトランザクション）：
// 1. claim で札を入れてみる。初めての札なら空の Optional（→ Service が作って、complete で番号を書く）
// 2. もう入っている札なら、中身の指紋を比べる。同じなら 1 回目に作ったものの番号を返す。ちがえば 409
// 札の記録は、追加と同じトランザクションの中なので、追加が失敗すれば札も一緒に取り消される（同じ札で送り直せる）
//
// JdbcTemplate は、JPA と同じトランザクション（同じ DB の接続）の中で動く（Spring がそうつないでくれる）
@Component
public class RequestTokens {

    // 画面が札を入れるヘッダーの名前
    public static final String HEADER = "Idempotency-Key";

    // UUID の決まった形（8-4-4-4-12 けたの 16 進）。UUID.fromString は「1-1-1-1-1」のような崩れた形も通すので、先にこれで確かめる
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final JdbcTemplate jdbc;

    public RequestTokens(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 札が何の追加のものか。DB の kind 列には小文字の値（task・column）で入れる
    public enum Kind {
        TASK("task"),
        COLUMN("column");

        private final String value;

        Kind(String value) {
            this.value = value;
        }
    }

    // 表 request_tokens の 1 行（もう入っていた札を読んだとき）
    private record StoredToken(String kind, String fingerprint, Long resourceId) {
    }

    // ヘッダーの文字を札（UUID）にする。ヘッダーがない（null）なら null（札なし＝今までどおり毎回作る）
    // 空文字・長すぎる・UUID の形でない、のどれかなら 400
    public static UUID parseKey(String header) {
        if (header == null) {
            return null;
        }
        if (!UUID_FORMAT.matcher(header).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "番号札（" + HEADER
                    + "）は、UUID の形（例：123e4567-e89b-12d3-a456-426614174000）で送ってください");
        }
        return UUID.fromString(header);
    }

    // 中身の指紋：渡された値を並べて、SHA-256 の 16 進（64 文字）にする
    // 値の区切りがあいまいにならないよう、1 つずつ「文字数:中身」の形にしてからつなぐ
    // （ただつなぐと、"ab"+"c" と "a"+"bc" が同じ指紋になってしまうため）。null は「-」にして、空文字（"0:"）と区別する
    public static String fingerprint(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            if (part == null) {
                joined.append("-;");
            } else {
                String text = part.toString();
                joined.append(text.length()).append(':').append(text).append(';');
            }
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 は、どの Java にも必ず入っている決まりなので、ここには来ない
            throw new IllegalStateException("SHA-256 が使えません", ex);
        }
    }

    // 24 時間をこえた札の行を消す。追加のたびに呼ぶ（専用の定期処理は作らない）
    // 時刻は DB の時計（now()）で比べる。created_at の既定値も DB の now() なので、同じ時計どうしで比べられる
    public void deleteExpired() {
        jdbc.update("DELETE FROM request_tokens WHERE created_at < now() - interval '24 hours'");
    }

    // 札を入れてみる。初めての札なら空の Optional を返す（呼んだ側が作って、complete で番号を書く）
    // もう入っている札なら：
    // - 種類と中身の指紋が同じ → 1 回目に作ったものの番号を返す（呼んだ側は新しく作らない）
    // - ちがう → 409
    // ON CONFLICT DO NOTHING：同じ札の行がもうあれば、エラーにせず何もしない（入れた行の数が 0 になる）
    // 同じ札が同時に来たときは、あとのほうがここで、先のほうのコミット（か取り消し）を待つ
    // （ふだんは、書き込みの鍵（WriteLockTransactionManager）で、ここに来る前に順番になっている）
    public Optional<Long> claim(UUID token, Kind kind, String fingerprint) {
        int inserted = jdbc.update("INSERT INTO request_tokens (token, kind, fingerprint) VALUES (?, ?, ?) "
                + "ON CONFLICT (token) DO NOTHING", token, kind.value, fingerprint);
        if (inserted == 1) {
            return Optional.empty();
        }

        List<StoredToken> stored = jdbc.query(
                "SELECT kind, fingerprint, resource_id FROM request_tokens WHERE token = ?",
                (row, rowNumber) -> new StoredToken(row.getString("kind"), row.getString("fingerprint"),
                        row.getObject("resource_id", Long.class)),
                token);
        // 行がない・作ったものの番号がない：先のほうが取り消された直後などで、記録が途中のまま見えた
        // 書き込みの鍵があるので、ふだんは起きない。起きても 500 にせず、503（少し待ってから送り直す）にする（設計書 2 節）
        // 409 にしないのは、画面が 409 を「ちがう中身」と受け取って札を取り替え、送り直しで 2 件できてしまうため
        if (stored.isEmpty() || stored.get(0).resourceId() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "同じ番号札の追加が、まだ終わっていません。少し待ってから、もう一度お試しください");
        }
        StoredToken found = stored.get(0);
        if (!found.kind().equals(kind.value) || !found.fingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "同じ番号札で、ちがう内容が送られました。新しい内容なら、新しい番号札で送ってください");
        }
        return Optional.of(found.resourceId());
    }

    // 初めての札で作ったものの番号を、札の行に書く（claim と同じトランザクションの中で呼ぶ）
    public void complete(UUID token, Long resourceId) {
        jdbc.update("UPDATE request_tokens SET resource_id = ? WHERE token = ?", resourceId, token);
    }
}
