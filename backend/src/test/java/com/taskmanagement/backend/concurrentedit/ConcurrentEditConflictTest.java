package com.taskmanagement.backend.concurrentedit;

import com.taskmanagement.backend.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節「版のずれ」「確かめる順番」のテスト（CE-20〜38。CE-39 は ConcurrentEditHandlerTest）
class ConcurrentEditConflictTest extends ConcurrentEditTestBase {

    // 400（JSON が読めない）の detail の頭
    private static final String UNREADABLE = "送られてきた内容を読み取れませんでした";

    // CE-20 の「current」が、T の今の状態（text B・version 1 など）になっているか
    private void assertCurrentIsTaskAtVersion1(MvcTestResult result, long t) {
        assertThat(((Number) read(result, "$.current.id")).longValue()).isEqualTo(t);
        assertThat((String) read(result, "$.current.text")).isEqualTo("B");
        assertThat(((Number) read(result, "$.current.columnId")).longValue()).isEqualTo(todo);
        assertThat((String) read(result, "$.current.priority")).isEqualTo("medium");
        assertThat(read(result, "$.current.dueDate") == null).isTrue();
        assertThat(((Number) read(result, "$.current.sortOrder")).doubleValue()).isEqualTo(taskSortOrder(t));
        assertThat(((Number) read(result, "$.current.version")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("CE-20 古い版の PUT は 409（VERSION_CONFLICT・現在の状態 current つき）で、タスクは変わらない")
    void ce20StalePutIsConflict() {
        long t = createTAtVersion1();
        String before = taskBody(t);

        MvcTestResult result = put("/api/tasks/" + t, taskJson("X", "high", 0));

        assertVersionConflict(result, DETAIL_TASK, true);
        assertCurrentIsTaskAtVersion1(result, t);
        assertThat(taskBody(t)).isEqualTo(before);
    }

    @Test
    @DisplayName("CE-21 version を書かない PUT は今までどおり 200（後勝ち）で、version は 2 になる")
    void ce21PutWithoutVersionIsLastWriteWins() {
        long t = createTAtVersion1();

        MvcTestResult result = put("/api/tasks/" + t, taskJson("X", "high", null));

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat((String) read(result, "$.text")).isEqualTo("X");
        assertThat(((Number) read(result, "$.version")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("CE-22 古い版の PATCH は 409 で T は todo のまま。version なしの同じ PATCH は 200")
    void ce22StalePatchIsConflict() {
        long t = createTAtVersion1();

        MvcTestResult stale = patch("/api/tasks/" + t, "{\"columnId\":" + done + ",\"version\":0}");
        assertVersionConflict(stale, DETAIL_TASK, true);
        assertCurrentIsTaskAtVersion1(stale, t);
        assertThat(taskColumn(t)).isEqualTo(todo);

        MvcTestResult withoutVersion = patch("/api/tasks/" + t, "{\"columnId\":" + done + "}");
        assertThat(statusOf(withoutVersion)).isEqualTo(200);
        assertThat(taskColumn(t)).isEqualTo(done);
    }

    @Test
    @DisplayName("CE-23 古い版の move は 409（current つき）で、T・U は何も変わらない。version なしの同じ move は 200")
    void ce23StaleMoveIsConflict() {
        long t = createTAtVersion1();
        long u = createTask("U", todo);
        String tBefore = taskBody(t);
        String uBefore = taskBody(u);

        MvcTestResult stale = put("/api/tasks/" + t + "/move",
                "{\"columnId\":" + doing + ",\"prevId\":null,\"version\":0}");
        assertVersionConflict(stale, DETAIL_TASK, true);
        assertThat(taskBody(t)).isEqualTo(tBefore);
        assertThat(taskBody(u)).isEqualTo(uBefore);

        MvcTestResult withoutVersion = put("/api/tasks/" + t + "/move",
                "{\"columnId\":" + doing + ",\"prevId\":null}");
        assertThat(statusOf(withoutVersion)).isEqualTo(200);
        assertThat(taskColumn(t)).isEqualTo(doing);
    }

    @Test
    @DisplayName("CE-24 reorder で 1 枚でも版が古いと 409（current なし）で、どのカードも動かない")
    void ce24StaleReorderIsConflict() {
        long t = createTAtVersion1();
        long u = createTask("U", todo);
        String tBefore = taskBody(t);
        String uBefore = taskBody(u);

        MvcTestResult result = put("/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":"
                + array(List.of(u, t)) + ",\"versions\":[0,0]}");

        assertVersionConflict(result, DETAIL_REORDER, false);
        assertThat(taskBody(t)).isEqualTo(tBefore);
        assertThat(taskBody(u)).isEqualTo(uBefore);
    }

    @Test
    @DisplayName("CE-25 reorder の versions の数がちがうと 400（何も変わらない）。versions を書かなければ 200 で並べ替わる")
    void ce25ReorderVersionsCount() {
        long a = createTask("A", todo);
        long b = createTask("B", todo);
        List<Long> before = taskIdsInOrder(todo);
        String ids = array(List.of(b, a));

        for (String versions : List.of("[0,0,0]", "[0]")) {
            MvcTestResult result = put("/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":" + ids
                    + ",\"versions\":" + versions + "}");
            assertThat(statusOf(result)).as(versions).isEqualTo(400);
            assertThat((String) read(result, "$.detail")).isEqualTo(DETAIL_VERSIONS_COUNT);
            assertThat(taskIdsInOrder(todo)).isEqualTo(before);
        }

        MvcTestResult withoutVersions = put("/api/tasks/reorder",
                "{\"columnId\":" + todo + ",\"orderedIds\":" + ids + "}");
        assertThat(statusOf(withoutVersions)).isEqualTo(200);
        List<Number> returned = read(withoutVersions, "$[*].id");
        assertThat(returned).extracting(Number::longValue).containsExactly(b, a);
        assertThat(taskIdsInOrder(todo)).containsExactly(b, a);
    }

    @Test
    @DisplayName("CE-26 ほかの列へ動かされたカードを reorder に入れると、版つきは 409（400 ではない）、版なしは 400")
    void ce26MovedCardInReorderIsConflictNotBadRequest() {
        long t = createTAtVersion1();
        long u = createTask("U", todo);
        assertThat(statusOf(patch("/api/tasks/" + t, "{\"columnId\":" + doing + ",\"version\":1}"))).isEqualTo(200);
        String tBefore = taskBody(t);
        String uBefore = taskBody(u);
        String body = "{\"columnId\":" + todo + ",\"orderedIds\":" + array(List.of(u, t));

        MvcTestResult withVersions = put("/api/tasks/reorder", body + ",\"versions\":[0,1]}");
        assertVersionConflict(withVersions, DETAIL_REORDER, false);

        MvcTestResult withoutVersions = put("/api/tasks/reorder", body + "}");
        assertThat(statusOf(withoutVersions)).isEqualTo(400);
        assertThat((String) read(withoutVersions, "$.detail")).contains("並べ替える列（columnId）にありません");

        assertThat(taskBody(t)).isEqualTo(tBefore);
        assertThat(taskBody(u)).isEqualTo(uBefore);
    }

    @Test
    @DisplayName("CE-27 reorder に消えたカードが混じっていても 200。消えたものは飛ばし、返事の配列にも入らない")
    void ce27DeletedCardInReorderIsSkipped() {
        Task a = saveTask("A", todo, 0);
        Task b = saveTask("B", todo, 1);
        Task c = saveTask("C", todo, 2);
        assertThat(statusOf(delete("/api/tasks/" + b.getId()))).isEqualTo(204);

        MvcTestResult result = put("/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":"
                + array(List.of(c.getId(), b.getId(), a.getId())) + ",\"versions\":[0,0,0]}");

        assertThat(statusOf(result)).isEqualTo(200);
        List<Number> returned = read(result, "$[*].id");
        assertThat(returned).extracting(Number::longValue).containsExactly(c.getId(), a.getId());
        assertThat(taskIdsInOrder(todo)).containsExactly(c.getId(), a.getId());
    }

    @Test
    @DisplayName("CE-28 タスクの DELETE：古い版は 409（残る）、合う版は 204、消えたあとは版が何でも 204、版なしは 204、版が数字でないと 400")
    void ce28DeleteTask() {
        long t = createTAtVersion1();
        long other = createTask("別", todo);
        long third = createTask("別2", todo);

        MvcTestResult stale = delete("/api/tasks/" + t + "?version=0");
        assertVersionConflict(stale, DETAIL_TASK, true);
        assertThat(taskText(t)).isEqualTo("B");

        assertThat(statusOf(delete("/api/tasks/" + t + "?version=1"))).isEqualTo(204);
        assertThat(statusOf(get("/api/tasks/" + t))).isEqualTo(404);

        assertThat(statusOf(delete("/api/tasks/" + t + "?version=7"))).isEqualTo(204);
        assertThat(statusOf(delete("/api/tasks/" + other))).isEqualTo(204);

        assertThat(statusOf(delete("/api/tasks/" + third + "?version=abc"))).isEqualTo(400);
        assertThat(statusOf(get("/api/tasks/" + third))).isEqualTo(200);
    }

    @Test
    @DisplayName("CE-29 古い版の列の PUT は 409（current に列の今の状態）で名前は変わらない。version なしは 200")
    void ce29StaleColumnRenameIsConflict() {
        long x = createColumn("確認待ち");
        assertThat(statusOf(put("/api/columns/" + x, "{\"name\":\"Y\",\"version\":0}"))).isEqualTo(200);

        MvcTestResult stale = put("/api/columns/" + x, "{\"name\":\"Z\",\"version\":0}");

        assertVersionConflict(stale, DETAIL_COLUMN, true);
        assertThat(((Number) read(stale, "$.current.id")).longValue()).isEqualTo(x);
        assertThat((String) read(stale, "$.current.name")).isEqualTo("Y");
        assertThat(((Number) read(stale, "$.current.version")).longValue()).isEqualTo(1);
        assertThat(columnName(x)).isEqualTo("Y");

        MvcTestResult withoutVersion = put("/api/columns/" + x, "{\"name\":\"Z\"}");
        assertThat(statusOf(withoutVersion)).isEqualTo(200);
        assertThat(columnName(x)).isEqualTo("Z");
    }

    @Test
    @DisplayName("CE-30 列の DELETE：古い版は 409（VERSION_CONFLICT）、合う版は 204、消えたあとは 204。基本の列・タスク入りの列は code なしの 409")
    void ce30DeleteColumn() {
        long x = createColumn("確認待ち");
        assertThat(statusOf(put("/api/columns/" + x, "{\"name\":\"確認待ち2\",\"version\":0}"))).isEqualTo(200);
        long y = createColumn("タスク入り");
        createTask("入っている", y);

        assertVersionConflict(delete("/api/columns/" + x + "?version=0"), DETAIL_COLUMN, true);
        assertThat(columnName(x)).isEqualTo("確認待ち2");

        assertThat(statusOf(delete("/api/columns/" + x + "?version=1"))).isEqualTo(204);
        assertThat(statusOf(delete("/api/columns/" + x + "?version=1"))).isEqualTo(204);

        // 基本の列（今の版 0 を送る）：版は合っているので、今までどおりの 409。code は付かない
        MvcTestResult fixed = delete("/api/columns/" + todo + "?version=0");
        assertThat(statusOf(fixed)).isEqualTo(409);
        assertThat(hasField(fixed, "code")).isFalse();
        assertThat((String) read(fixed, "$.detail")).contains("消せません");

        MvcTestResult withTasks = delete("/api/columns/" + y + "?version=0");
        assertThat(statusOf(withTasks)).isEqualTo(409);
        assertThat(hasField(withTasks, "code")).isFalse();
        assertThat((String) read(withTasks, "$.detail")).contains("タスクが入っている列は消せません");
    }

    @Test
    @DisplayName("CE-31 列の並べ替え：古い版は 409（current なし・何も変わらない）、数ちがいは 400、列不足は 400（先に判定）、合う版・版なしは 200")
    void ce31ColumnOrder() {
        long x = createColumn("確認待ち");
        assertThat(statusOf(put("/api/columns/" + x, "{\"name\":\"確認待ち2\",\"version\":0}"))).isEqualTo(200);
        List<Long> original = columnIdsInOrder();
        List<Long> swapped = List.of(doing, todo, done, x);
        String ids = array(swapped);
        String currentVersions = currentColumnVersions(swapped);
        List<Number> versionsBefore = read(get("/api/columns"), "$[*].version");

        // 1 つ古い（x の版を 1 つ戻す）
        String stale = array(List.of(columnVersion(doing), columnVersion(todo), columnVersion(done),
                columnVersion(x) - 1));
        MvcTestResult staleResult = put("/api/columns/order",
                "{\"orderedIds\":" + ids + ",\"versions\":" + stale + "}");
        assertVersionConflict(staleResult, DETAIL_COLUMN_ORDER, false);
        assertThat(columnIdsInOrder()).isEqualTo(original);
        List<Number> versionsAfter = read(get("/api/columns"), "$[*].version");
        assertThat(versionsAfter).isEqualTo(versionsBefore);

        // 数がちがう
        MvcTestResult countResult = put("/api/columns/order", "{\"orderedIds\":" + ids + ",\"versions\":[0,0,0]}");
        assertThat(statusOf(countResult)).isEqualTo(400);
        assertThat((String) read(countResult, "$.detail")).isEqualTo(DETAIL_VERSIONS_COUNT);

        // 列が足りない（versions の数は合わせてある）：列の判定が先
        MvcTestResult missing = put("/api/columns/order", "{\"orderedIds\":" + array(List.of(doing, todo, done))
                + ",\"versions\":[0,0,0]}");
        assertThat(statusOf(missing)).isEqualTo(400);
        assertThat((String) read(missing, "$.detail")).contains("全部の列の ID");

        // 今の版：200
        MvcTestResult ok = put("/api/columns/order",
                "{\"orderedIds\":" + ids + ",\"versions\":" + currentVersions + "}");
        assertThat(statusOf(ok)).isEqualTo(200);

        // versions なし：200
        MvcTestResult noVersions = put("/api/columns/order", "{\"orderedIds\":" + array(original) + "}");
        assertThat(statusOf(noVersions)).isEqualTo(200);
        assertThat(columnIdsInOrder()).isEqualTo(original);
    }

    @Test
    @DisplayName("CE-32 version・versions が数字でないと 400（JSON が読めない）で、何も変わらない")
    void ce32NonNumericVersionIsBadRequest() {
        long t = createT();
        String before = taskBody(t);

        List<MvcTestResult> responses = List.of(
                put("/api/tasks/" + t, "{\"text\":\"X\",\"priority\":\"high\",\"dueDate\":null,\"version\":\"abc\"}"),
                patch("/api/tasks/" + t, "{\"columnId\":" + done + ",\"version\":\"abc\"}"),
                put("/api/tasks/" + t + "/move",
                        "{\"columnId\":" + doing + ",\"prevId\":null,\"version\":\"abc\"}"),
                put("/api/tasks/reorder",
                        "{\"columnId\":" + todo + ",\"orderedIds\":[" + t + "],\"versions\":[\"x\"]}"),
                put("/api/columns/order", "{\"orderedIds\":" + array(columnIdsInOrder()) + ",\"versions\":[\"x\"]}"));

        for (MvcTestResult response : responses) {
            assertThat(statusOf(response)).as(bodyOf(response)).isEqualTo(400);
            assertThat((String) read(response, "$.detail")).startsWith(UNREADABLE);
        }
        assertThat(taskBody(t)).isEqualTo(before);
    }

    // ---- 確かめる順番 ----

    @Test
    @DisplayName("CE-35 ない id のタスクへの PUT・PATCH・move は、版つきでも 404（409 ではない）")
    void ce35MissingTaskIsNotFoundBeforeVersion() {
        long missing = 999_999L;

        assertThat(statusOf(put("/api/tasks/" + missing, taskJson("X", "high", 0)))).isEqualTo(404);
        assertThat(statusOf(patch("/api/tasks/" + missing, "{\"columnId\":" + done + ",\"version\":0}")))
                .isEqualTo(404);
        assertThat(statusOf(put("/api/tasks/" + missing + "/move",
                "{\"columnId\":" + doing + ",\"prevId\":null,\"version\":0}"))).isEqualTo(404);
    }

    @Test
    @DisplayName("CE-36 古い版でも、text が空なら 400（入力チェックが先）。ない列の columnId なら 409（版が先）")
    void ce36ValidationBeforeVersionBeforeOtherRules() {
        long t = createTAtVersion1();

        MvcTestResult blank = put("/api/tasks/" + t, taskJson("", "high", 0));
        assertThat(statusOf(blank)).isEqualTo(400);

        MvcTestResult putMissingColumn = put("/api/tasks/" + t,
                "{\"text\":\"X\",\"priority\":\"high\",\"dueDate\":null,\"columnId\":999999,\"version\":0}");
        assertVersionConflict(putMissingColumn, DETAIL_TASK, true);

        MvcTestResult patchMissingColumn = patch("/api/tasks/" + t, "{\"columnId\":999999,\"version\":0}");
        assertVersionConflict(patchMissingColumn, DETAIL_TASK, true);
    }

    @Test
    @DisplayName("CE-37 move：版が古ければ、prevId が別の列でも 409。今の版なら今までどおり 400")
    void ce37VersionBeforePrevIdRule() {
        long t = createTAtVersion1();
        long v = createTask("V", doing);
        String body = "{\"columnId\":" + todo + ",\"prevId\":" + v + ",\"version\":";

        assertVersionConflict(put("/api/tasks/" + t + "/move", body + "0}"), DETAIL_TASK, true);

        MvcTestResult current = put("/api/tasks/" + t + "/move", body + "1}");
        assertThat(statusOf(current)).isEqualTo(400);
        assertThat((String) read(current, "$.detail")).contains("上のカード（prevId）が、移動先の列にありません");
    }

    @Test
    @DisplayName("CE-38 基本の列の DELETE でも、版が古ければ VERSION_CONFLICT の 409（版が先）")
    void ce38VersionBeforeFixedColumnRule() {
        assertThat(statusOf(put("/api/columns/" + todo, "{\"name\":\"Renamed\",\"version\":0}"))).isEqualTo(200);

        MvcTestResult result = delete("/api/columns/" + todo + "?version=0");

        assertVersionConflict(result, DETAIL_COLUMN, true);
        assertThat(columnName(todo)).isEqualTo("Renamed");
    }
}
