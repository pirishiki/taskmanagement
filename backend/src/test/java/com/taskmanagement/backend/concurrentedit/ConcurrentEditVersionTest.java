package com.taskmanagement.backend.concurrentedit;

import com.jayway.jsonpath.JsonPath;
import com.taskmanagement.backend.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節「版の付き方」のテスト（CE-01〜12。CE-13 は ConcurrentEditMigrationTest）
class ConcurrentEditVersionTest extends ConcurrentEditTestBase {

    @Test
    @DisplayName("CE-01 タスクを作ると version 0 で返り、一覧の各要素・1 件の返事にも version がある")
    void ce01TaskHasVersionZero() {
        MvcTestResult created = post("/api/tasks", "{\"text\":\"A\",\"columnId\":" + todo + "}");
        long t = idOf(created);
        createTask("B", todo);

        assertThat(statusOf(created)).isEqualTo(201);
        assertThat(((Number) read(created, "$.version")).longValue()).isZero();
        List<Number> versions = read(get("/api/tasks"), "$[*].version");
        assertThat(versions).hasSize(2);
        assertThat(versions).extracting(Number::longValue).containsOnly(0L);
        assertThat(taskVersion(t)).isZero();
    }

    @Test
    @DisplayName("CE-02 列を作ると version 0 で返り、列の一覧の各要素（基本の 3 列も）に version 0 がある")
    void ce02ColumnHasVersionZero() {
        MvcTestResult created = post("/api/columns", "{\"name\":\"確認待ち\"}");

        assertThat(statusOf(created)).isEqualTo(201);
        assertThat(((Number) read(created, "$.version")).longValue()).isZero();
        List<Number> versions = read(get("/api/columns"), "$[*].version");
        assertThat(versions).hasSize(4);
        assertThat(versions).extracting(Number::longValue).containsOnly(0L);
    }

    @Test
    @DisplayName("CE-03 PUT で中身を変えるたびに version がちょうど 1 ずつ上がる")
    void ce03PutBumpsVersionByOne() {
        long t = createT();

        MvcTestResult first = put("/api/tasks/" + t, taskJson("B", "medium", 0));
        MvcTestResult second = put("/api/tasks/" + t, taskJson("C", "medium", 1));

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(((Number) read(first, "$.version")).longValue()).isEqualTo(1);
        assertThat((String) read(first, "$.text")).isEqualTo("B");
        assertThat(statusOf(second)).isEqualTo(200);
        assertThat(((Number) read(second, "$.version")).longValue()).isEqualTo(2);
        assertThat((String) read(second, "$.text")).isEqualTo("C");
    }

    @Test
    @DisplayName("CE-04 PUT で今と同じ中身を送っても 200 で、version は上がらない")
    void ce04PutWithSameContentKeepsVersion() {
        long t = createT();

        MvcTestResult result = put("/api/tasks/" + t, taskJson("A", "medium", 0));

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(((Number) read(result, "$.version")).longValue()).isZero();
        assertThat(taskVersion(t)).isZero();
    }

    @Test
    @DisplayName("CE-05 PATCH で列を変えると version が 1 上がり、同じ列への PATCH では上がらない")
    void ce05PatchBumpsVersionOnlyWhenChanged() {
        long t = createT();

        MvcTestResult first = patch("/api/tasks/" + t, "{\"columnId\":" + done + ",\"version\":0}");
        MvcTestResult second = patch("/api/tasks/" + t, "{\"columnId\":" + done + ",\"version\":1}");

        assertThat(statusOf(first)).isEqualTo(200);
        assertThat(((Number) read(first, "$.columnId")).longValue()).isEqualTo(done);
        assertThat(((Number) read(first, "$.version")).longValue()).isEqualTo(1);
        assertThat(statusOf(second)).isEqualTo(200);
        assertThat(((Number) read(second, "$.version")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("CE-06 move は動かしたカードだけ version が 1 上がり、ほかのカードは上がらない")
    void ce06MoveBumpsOnlyMovedTask() {
        long t = createT();
        long u = createTask("U", todo);

        MvcTestResult result = put("/api/tasks/" + t + "/move",
                "{\"columnId\":" + doing + ",\"prevId\":null,\"version\":0}");

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(taskVersion(t)).isEqualTo(1);
        assertThat(taskVersion(u)).isZero();
    }

    @Test
    @DisplayName("CE-07 move で番号を振り直しても、動かしたカード以外（P・Q）の version は上がらない")
    void ce07RenumberDoesNotBumpVersion() {
        // 準備：P と Q の番号のすき間がなく、間に入れられない（振り直しになる）
        Task p = saveTask("P", todo, 1.0);
        Task q = saveTask("Q", todo, Math.nextUp(1.0));
        Task r = saveTask("R", todo, 5.0);

        MvcTestResult result = put("/api/tasks/" + r.getId() + "/move",
                "{\"columnId\":" + todo + ",\"prevId\":" + p.getId() + ",\"version\":0}");

        assertThat(statusOf(result)).isEqualTo(200);
        assertThat(taskIdsInOrder(todo)).containsExactly(p.getId(), r.getId(), q.getId());
        // 振り直しが本当に起きた（P・Q が 0・1 になり、R はその真ん中）。起きていなければ、版の確かめの意味がない
        assertThat(taskSortOrder(p.getId())).isZero();
        assertThat(taskSortOrder(q.getId())).isEqualTo(1.0);
        assertThat(taskSortOrder(r.getId())).isEqualTo(0.5);
        assertThat(taskVersion(r.getId())).isEqualTo(1);
        assertThat(taskVersion(p.getId())).isZero();
        assertThat(taskVersion(q.getId())).isZero();
    }

    @Test
    @DisplayName("CE-08 reorder は 200 で、並べ直したタスクを orderedIds の順の配列で返し、席が変わったものだけ version が上がる")
    void ce08ReorderReturnsTasksAndBumpsChangedOnly() {
        Task a = saveTask("A", todo, 0);
        Task b = saveTask("B", todo, 1);
        Task c = saveTask("C", todo, 2);

        MvcTestResult result = put("/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":"
                + array(List.of(c.getId(), b.getId(), a.getId())) + ",\"versions\":[0,0,0]}");

        assertThat(statusOf(result)).isEqualTo(200);
        List<Number> ids = read(result, "$[*].id");
        assertThat(ids).extracting(Number::longValue).containsExactly(c.getId(), b.getId(), a.getId());
        List<Number> sortOrders = read(result, "$[*].sortOrder");
        assertThat(sortOrders).extracting(Number::doubleValue).containsExactly(0.0, 1.0, 2.0);
        List<Number> versions = read(result, "$[*].version");
        assertThat(versions).extracting(Number::longValue).containsExactly(1L, 0L, 1L);
    }

    @Test
    @DisplayName("CE-09 import したあとは、すべての列・タスクの version が 0")
    void ce09ImportResetsVersionToZero() {
        long extra = createColumn("確認待ち");
        long t = createTask("A", extra);
        assertThat(statusOf(put("/api/tasks/" + t, taskJson("B", "high", 0)))).isEqualTo(200);
        String exported = bodyOf(get("/api/tasks/export"));

        MvcTestResult imported = post("/api/tasks/import", exported);

        assertThat(statusOf(imported)).isEqualTo(204);
        List<Number> taskVersions = read(get("/api/tasks"), "$[*].version");
        assertThat(taskVersions).isNotEmpty();
        assertThat(taskVersions).extracting(Number::longValue).containsOnly(0L);
        List<Number> columnVersions = read(get("/api/columns"), "$[*].version");
        assertThat(columnVersions).hasSize(4);
        assertThat(columnVersions).extracting(Number::longValue).containsOnly(0L);
    }

    @Test
    @DisplayName("CE-10 列の名前の変更と並べ替えで、中身が変わった列だけ version が 1 上がる")
    void ce10ColumnRenameAndReorderBumpVersion() {
        long x = createColumn("確認待ち");

        MvcTestResult renamed = put("/api/columns/" + x, "{\"name\":\"新しい名前\",\"version\":0}");
        assertThat(statusOf(renamed)).isEqualTo(200);
        assertThat(((Number) read(renamed, "$.version")).longValue()).isEqualTo(1);

        // X を一番左にする：全部の列の番号が変わるので、全部 1 上がる（X は 1 → 2、ほかは 0 → 1）
        List<Long> first = List.of(x, todo, doing, done);
        MvcTestResult reordered = put("/api/columns/order", "{\"orderedIds\":" + array(first)
                + ",\"versions\":" + currentColumnVersions(first) + "}");
        assertThat(statusOf(reordered)).isEqualTo(200);
        List<Number> versions = read(reordered, "$[*].version");
        assertThat(versions).extracting(Number::longValue).containsExactly(2L, 1L, 1L, 1L);

        // 先頭の 2 つだけ入れ替える：番号が変わらない doing・done は、そのまま
        List<Long> second = List.of(todo, x, doing, done);
        MvcTestResult swapped = put("/api/columns/order", "{\"orderedIds\":" + array(second)
                + ",\"versions\":" + currentColumnVersions(second) + "}");
        assertThat(statusOf(swapped)).isEqualTo(200);
        List<Number> after = read(swapped, "$[*].version");
        assertThat(after).extracting(Number::longValue).containsExactly(2L, 3L, 1L, 1L);
    }

    @Test
    @DisplayName("CE-11 番号札の送り直しは、1 回目に作ったものの今の状態（直したあとの text・version）を返す")
    void ce11IdempotentReplayReturnsCurrentState() {
        String key = UUID.randomUUID().toString();
        String body = "{\"text\":\"A\",\"columnId\":" + todo + "}";
        MvcTestResult first = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body).exchange();
        long id = idOf(first);
        assertThat(statusOf(put("/api/tasks/" + id, taskJson("直した", "medium", 0)))).isEqualTo(200);

        MvcTestResult again = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body).exchange();

        assertThat(statusOf(again)).isEqualTo(201);
        assertThat(idOf(again)).isEqualTo(id);
        assertThat((String) read(again, "$.text")).isEqualTo("直した");
        assertThat(((Number) read(again, "$.version")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("CE-12 POST に version を書いても無視され（version 0）、書いた値が違う送り直しも同じ中身として扱われる")
    void ce12PostIgnoresVersion() {
        String key = UUID.randomUUID().toString();
        MvcTestResult first = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"text\":\"A\",\"columnId\":" + todo + ",\"version\":5}").exchange();
        MvcTestResult second = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"text\":\"A\",\"columnId\":" + todo + ",\"version\":9}").exchange();

        assertThat(statusOf(first)).isEqualTo(201);
        assertThat(((Number) JsonPath.read(bodyOf(first), "$.version")).longValue()).isZero();
        assertThat(statusOf(second)).isEqualTo(201);
        assertThat(idOf(second)).isEqualTo(idOf(first));
    }
}
