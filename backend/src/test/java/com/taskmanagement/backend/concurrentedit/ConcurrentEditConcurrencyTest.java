package com.taskmanagement.backend.concurrentedit;

import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節「同時に送る」のテスト（CE-40〜44）
// DoubleSubmitConcurrencyTest と同じ作り：全部のスレッドを同じ合図で一斉に送り、場面ごとに何ラウンドも繰り返し、
// 返事だけでなく、そのあとの DB の状態まで確かめる
class ConcurrentEditConcurrencyTest extends ConcurrentEditTestBase {

    private static final int ROUNDS = 10;

    // import ファイル（基本の列 3 つ）。CE-44 で使う
    private static final String IMPORT_JSON = """
            {"version": 2, "columns": [
              {"name": "I-todo", "fixed": true, "tasks": [{"text": "I-1", "priority": "high"}]},
              {"name": "I-doing", "fixed": true, "tasks": []},
              {"name": "I-done", "fixed": true, "done": true, "tasks": []}
            ]}
            """;

    // 渡された呼び出しを、それぞれ別のスレッドで、合図と同時に一斉に実行して、返事を呼び出しの順に返す
    private List<MvcTestResult> runConcurrently(List<Supplier<MvcTestResult>> calls) {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (Supplier<MvcTestResult> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.get();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                // 60 秒待っても返らないときは、デッドロックと見なして失敗にする
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    pool.shutdownNow();
                    throw e;
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Integer> sortedStatuses(List<MvcTestResult> results) {
        return results.stream().map(ConcurrentEditTestBase::statusOf).sorted().toList();
    }

    @Test
    @DisplayName("CE-40 同じ版の PUT を 2 本同時に送ると、毎ラウンド、1 本が 200・1 本が 409。DB は 200 だったほうの文字で version 1")
    void ce40TwoPutsWithSameVersion() {
        for (int round = 0; round < ROUNDS; round++) {
            long t = createT();

            List<MvcTestResult> results = runConcurrently(List.of(
                    () -> put("/api/tasks/" + t, taskJson("X", "medium", 0)),
                    () -> put("/api/tasks/" + t, taskJson("Y", "medium", 0))));

            assertThat(sortedStatuses(results)).as("round " + round).containsExactly(200, 409);
            String winner = statusOf(results.get(0)) == 200 ? "X" : "Y";
            assertThat(taskText(t)).isEqualTo(winner);
            assertThat(taskVersion(t)).isEqualTo(1);
            taskRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("CE-41 PUT と DELETE を同時に送ると、両方は成功しない（PUT 200 なら DELETE 409、DELETE 204 なら PUT 404）。500 にならない")
    void ce41PutAndDelete() {
        for (int round = 0; round < ROUNDS; round++) {
            long t = createT();

            List<MvcTestResult> results = runConcurrently(List.of(
                    () -> put("/api/tasks/" + t, taskJson("X", "medium", 0)),
                    () -> delete("/api/tasks/" + t + "?version=0")));

            int putStatus = statusOf(results.get(0));
            int deleteStatus = statusOf(results.get(1));
            if (putStatus == 200) {
                assertThat(deleteStatus).as("round " + round).isEqualTo(409);
                assertThat(taskText(t)).isEqualTo("X");
            } else {
                assertThat(putStatus).as("round " + round).isEqualTo(404);
                assertThat(deleteStatus).isEqualTo(204);
                assertThat(taskRepository.existsById(t)).isFalse();
            }
            taskRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("CE-42 PATCH（完了）と move を同じ版で同時に送ると、1 本が 200・1 本が 409。T は 200 だったほうの列にある")
    void ce42PatchAndMove() {
        for (int round = 0; round < ROUNDS; round++) {
            long t = createT();

            List<MvcTestResult> results = runConcurrently(List.of(
                    () -> patch("/api/tasks/" + t, "{\"columnId\":" + done + ",\"version\":0}"),
                    () -> put("/api/tasks/" + t + "/move",
                            "{\"columnId\":" + doing + ",\"prevId\":null,\"version\":0}")));

            assertThat(sortedStatuses(results)).as("round " + round).containsExactly(200, 409);
            Long expectedColumn = statusOf(results.get(0)) == 200 ? done : doing;
            assertThat(taskColumn(t)).isEqualTo(expectedColumn);
            taskRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("CE-43 reorder と、その中の 1 枚への PUT を同じ版で同時に送ると、1 本が成功（200）・1 本が 409")
    void ce43ReorderAndPut() {
        for (int round = 0; round < ROUNDS; round++) {
            Task a = saveTask("A", todo, 0);
            Task b = saveTask("B", todo, 1);

            List<MvcTestResult> results = runConcurrently(List.of(
                    () -> put("/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":"
                            + array(List.of(b.getId(), a.getId())) + ",\"versions\":[0,0]}"),
                    () -> put("/api/tasks/" + a.getId(), taskJson("A2", "medium", 0))));

            assertThat(sortedStatuses(results)).as("round " + round).containsExactly(200, 409);
            // 409 だったほうは、何も書いていない
            if (statusOf(results.get(0)) == 200) {
                // reorder が先：並びは B → A。PUT は止まったので、A の名前は A のまま
                assertThat(taskIdsInOrder(todo)).as("round " + round).containsExactly(b.getId(), a.getId());
                assertThat(taskText(a.getId())).isEqualTo("A");
            } else {
                // PUT が先：A の名前は A2。reorder は止まったので、並びは A → B のまま
                assertThat(taskIdsInOrder(todo)).as("round " + round).containsExactly(a.getId(), b.getId());
                assertThat(taskText(a.getId())).isEqualTo("A2");
            }
            taskRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("CE-44 import と PUT を同時に送ると、import は 204、PUT は 200（import の前）か 404（後）。500 にならない")
    void ce44ImportAndPut() {
        for (int round = 0; round < ROUNDS; round++) {
            long t = createT();

            List<MvcTestResult> results = runConcurrently(List.of(
                    () -> post("/api/tasks/import", IMPORT_JSON),
                    () -> put("/api/tasks/" + t, taskJson("X", "medium", 0))));

            assertThat(statusOf(results.get(0))).as("round " + round).isEqualTo(204);
            assertThat(statusOf(results.get(1))).isIn(200, 404);
            // どちらの順でも、最後は import のファイルの中身だけ（PUT が先でも、import に置き換えられる）。版はどれも 0
            List<String> names = columnRepository.findAllByOrderBySortOrderAsc().stream()
                    .map(BoardColumn::getName).toList();
            assertThat(names).as("round " + round).containsExactly("I-todo", "I-doing", "I-done");
            List<Task> tasks = taskRepository.findAll();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getText()).isEqualTo("I-1");
            assertThat(tasks.get(0).getVersion()).isZero();
            resetBoard();
        }
    }
}
