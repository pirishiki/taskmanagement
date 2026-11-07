package com.taskmanagement.backend.concurrentedit;

import com.taskmanagement.backend.write.WriteLockTransactionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節 CE-47b：ボードの番号の確かめと、テストデータの投入がすれ違ったとき
// 書き込みが「Controller の前の確かめ」（BoardIdInterceptor）を前の番号のまま通ったあと、書き込みの鍵を待っている間に、
// 投入（db/seed-test-data.sql。アプリと同じ鍵をとって、番号を振り直し、ボードの番号を新しくする）が終わることがある
// そのまま書くと、投入された「番号（id）が同じ別のカード」（版も 0 どうし）を書き換えてしまうので、鍵をとったあとにもう一度比べる
// 投入の代わりに、別の接続でアプリと同じ鍵を握り、ボードの番号とカードを SQL で書き換えてからコミットする
class ConcurrentEditBoardIdRaceTest extends ConcurrentEditTestBase {

    // 鍵を握るための、アプリとは別の DB の接続を借りる
    @Autowired
    private DataSource dataSource;

    // 鍵を待っている書き込みがあるか（PostgreSQL の pg_locks で、まだ取れていない advisory の鍵を数える）
    private boolean someoneIsWaitingForLock() {
        Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class);
        return waiting != null && waiting > 0;
    }

    @Test
    @DisplayName("CE-47b 確かめを前の番号で通った書き込みが、鍵を待つ間に投入が終わると、鍵をとったあとの確かめで 409（BOARD_CHANGED）。"
            + "投入されたカードは書き換わらない")
    void ce47bBoardChangedWhileWaitingForLock() throws Exception {
        long t = createT();
        String boardId = get("/api/columns").getResponse().getHeader("Board-Id");
        assertThat(boardId).isNotNull();

        // try の終わりに、接続 → スレッドの順で閉じる（途中で失敗しても、接続を閉じれば鍵が外れ、待っている PUT が終わる）
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
                Connection holder = dataSource.getConnection()) {
            // 準備：投入の代わりに、別の接続でトランザクションを始め、アプリと同じ鍵を握る
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, WriteLockTransactionManager.LOCK_KEY);
                statement.execute();
            }

            // 実行：今の（まだ変わっていない）ボードの番号を付けて、版 0 で PUT する。確かめは通り、鍵を待つ
            Future<MvcTestResult> pending = pool.submit(() -> mvc.put().uri("/api/tasks/" + t)
                    .contentType(MediaType.APPLICATION_JSON).header("Board-Id", boardId)
                    .content(taskJson("古い画面の入力", "high", 0)).exchange());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!someoneIsWaitingForLock()) {
                assertThat(System.nanoTime()).as("PUT が鍵を待ち始めない").isLessThan(deadline);
                Thread.onSpinWait();
            }

            // 投入と同じことをして、コミットする（鍵が外れ、待っていた PUT が動き出す）
            // カードは SQL で直接書くので、版は 0 のまま（投入で入れ直したカードと同じ）
            try (Statement statement = holder.createStatement()) {
                statement.executeUpdate("UPDATE board_meta SET board_id = gen_random_uuid()");
                statement.executeUpdate("UPDATE tasks SET text = '投入されたカード' WHERE id = " + t);
            }
            holder.commit();

            // 確かめる：PUT は 409（BOARD_CHANGED）。投入されたカードは書き換わっていない
            MvcTestResult result = pending.get(30, TimeUnit.SECONDS);
            assertBoardChanged(result);
            assertThat(taskText(t)).isEqualTo("投入されたカード");

            // 止めたあと、鍵は外れている：新しい番号を付けた次の書き込みは、待たずに 200
            String newBoardId = get("/api/columns").getResponse().getHeader("Board-Id");
            MvcTestResult next = mvc.put().uri("/api/tasks/" + t).contentType(MediaType.APPLICATION_JSON)
                    .header("Board-Id", newBoardId).content(taskJson("新しい画面の入力", "high", 0)).exchange();
            assertThat(statusOf(next)).as(bodyOf(next)).isEqualTo(200);
            assertThat(taskText(t)).isEqualTo("新しい画面の入力");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
