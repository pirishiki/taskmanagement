package com.taskmanagement.backend;

import com.jayway.jsonpath.JsonPath;
import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
import com.taskmanagement.backend.task.Task;
import com.taskmanagement.backend.task.TaskPriority;
import com.taskmanagement.backend.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// 二重送信の設計書（docs/二重送信の設計書.md）5.1 節の、同時に送るテスト
// DS-06・16・17（タスク）、DS-24・25（列）、DS-30〜33（import と書き込みの順番）
// 運よく通るだけにならないよう、(1) 全部のスレッドを同じ合図で一斉にスタートさせ、(2) 何本も（5〜8 本）送り、
// (3) 場面ごとに何ラウンドも繰り返し、(4) 返事だけでなく、そのあとの DB の件数・並び・状態まで確かめる
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DoubleSubmitConcurrencyTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    // 同時に送る本数と、繰り返すラウンド数
    private static final int THREADS = 8;
    private static final int ROUNDS = 5;

    // DS-35 の import ファイルに足す列の数
    // 書き出しは「列の一覧を読む → 列ごとにタスクを読む」と何回も DB に聞くので、列が多いほど、途中で import が終わる
    // すき間が広くなる（1 つの状態を最後まで読む作りでないと、混ざったファイルが出やすくなる）
    private static final int EXTRA_COLUMNS = 20;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BoardColumnRepository columnRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Long todo;

    // テストごとに、3列だけ・タスクが 1 件もない状態にする
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        columnRepository.save(new BoardColumn("進行中", 1.0, true, false));
        columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true));
    }

    // ---- 道具 ----

    // 渡された呼び出しを、それぞれ別のスレッドで、合図と同時に一斉に実行して、返事を呼び出しの順に返す
    private List<MvcTestResult> runConcurrently(List<Supplier<MvcTestResult>> calls) {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        // Java 21 の ExecutorService は try-with-resources で閉じられる（閉じるときに、実行中の仕事の終わりを待つ）
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (Supplier<MvcTestResult> call : calls) {
                futures.add(pool.submit(() -> {
                    // 全員が用意できたら、main が合図（start）を出す。それまで全員ここで待つ
                    ready.countDown();
                    start.await();
                    return call.get();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                // 60 秒待っても返らないときは、鍵が残ったまま止まっている（デッドロック）と見なして失敗にする
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    // 止まったままの仕事を中断してから失敗にする（閉じるときに待ち続けないように）
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

    private static int statusOf(MvcTestResult result) {
        return result.getResponse().getStatus();
    }

    private static String bodyOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static long idOf(MvcTestResult result) {
        return ((Number) JsonPath.read(bodyOf(result), "$.id")).longValue();
    }

    private MvcTestResult postTask(String key, String json) {
        var request = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header(KEY_HEADER, key);
        }
        return request.exchange();
    }

    private MvcTestResult postColumn(String key, String name) {
        var request = mvc.post().uri("/api/columns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\"}");
        if (key != null) {
            request.header(KEY_HEADER, key);
        }
        return request.exchange();
    }

    private MvcTestResult importFile(String json) {
        return mvc.post().uri("/api/tasks/import").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    // 名前と文章の頭に prefix を付けた、基本の列 3 つ（完了の列つき）と、タスク 3 件の import ファイル
    // 2 つの import の内容が混ざったら、名前や文章の頭を見て分かるようにしている
    private static String importJson(String prefix) {
        return """
                {"version": 2, "columns": [
                  {"name": "%1$s-todo", "fixed": true, "tasks": [
                    {"text": "%1$s-1", "priority": "high"},
                    {"text": "%1$s-2", "priority": "low"}
                  ]},
                  {"name": "%1$s-doing", "fixed": true, "tasks": []},
                  {"name": "%1$s-done", "fixed": true, "done": true, "tasks": [
                    {"text": "%1$s-3", "priority": "medium"}
                  ]}
                ]}
                """.formatted(prefix);
    }

    private List<String> columnNames() {
        return columnRepository.findAllByOrderBySortOrderAsc().stream().map(BoardColumn::getName).toList();
    }

    // import のあとの状態が、prefix のファイルの内容だけになっているか（もう一方の内容が混ざっていないか）
    private boolean boardIsExactly(String prefix) {
        List<String> texts = taskRepository.findAll().stream().map(Task::getText).sorted().toList();
        return columnNames().equals(List.of(prefix + "-todo", prefix + "-doing", prefix + "-done"))
                && texts.equals(List.of(prefix + "-1", prefix + "-2", prefix + "-3"));
    }

    // 列は「完了の列がちょうど 1 つ」で、どのタスクも、今ある列の中にいる（消えた列を指すタスクがない）
    private void assertBoardIsConsistent() {
        assertThat(columnRepository.findAll().stream().filter(BoardColumn::isDone).count()).isEqualTo(1);
        Integer orphans = jdbc.queryForObject("SELECT count(*) FROM tasks t WHERE NOT EXISTS "
                + "(SELECT 1 FROM board_columns c WHERE c.id = t.column_id)", Integer.class);
        assertThat(orphans).isZero();
    }

    // GET /api/tasks の返事から、その列のタスクの id を、返ってきた順に取り出す
    private List<Long> taskIdsInOrder(Long columnId) {
        String body = bodyOf(mvc.get().uri("/api/tasks").exchange());
        List<Number> ids = JsonPath.read(body, "$[?(@.columnId == " + columnId + ")].id");
        return ids.stream().map(Number::longValue).toList();
    }

    // ---- DS-06 ----

    @Test
    @DisplayName("DS-06 同じタスクの削除を同時に送ると、どちらも 204（500 にならない）で、タスクは消える")
    void ds06ConcurrentDeleteOfSameTask() {
        for (int round = 0; round < ROUNDS; round++) {
            Task task = taskRepository.save(new Task("消すタスク", todo, TaskPriority.MEDIUM, null, 0.0));
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> mvc.delete().uri("/api/tasks/{id}", task.getId()).exchange());
            }

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(204);
            assertThat(taskRepository.existsById(task.getId())).isFalse();
        }
    }

    // ---- DS-16・DS-17 ----

    @Test
    @DisplayName("DS-16 同じ札・同じ中身のタスク追加を同時に送ると、どちらも 201 で同じ id になり、タスクは 1 件")
    void ds16ConcurrentCreateWithSameKey() {
        for (int round = 0; round < ROUNDS; round++) {
            String key = UUID.randomUUID().toString();
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> postTask(key, "{\"text\": \"牛乳を買う\", \"priority\": \"high\"}"));
            }

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(201);
            assertThat(results).extracting(DoubleSubmitConcurrencyTest::idOf).containsOnly(idOf(results.get(0)));
            assertThat(taskRepository.count()).isEqualTo(1);
            Integer rows = jdbc.queryForObject("SELECT count(*) FROM request_tokens WHERE token = ?::uuid",
                    Integer.class, key);
            assertThat(rows).isEqualTo(1);
            // 次のラウンドのために空に戻す
            taskRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("DS-17 ちがう札で同時にタスク追加を送ると、全部できて、並びは毎回同じ（並び順の数字が同じでも id で決まる）")
    void ds17ConcurrentCreateWithDifferentKeys() {
        List<Supplier<MvcTestResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String text = "タスク" + i;
            calls.add(() -> postTask(UUID.randomUUID().toString(), "{\"text\": \"" + text + "\"}"));
        }

        List<MvcTestResult> results = runConcurrently(calls);

        assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(201);
        assertThat(results).extracting(DoubleSubmitConcurrencyTest::idOf).doesNotHaveDuplicates();
        assertThat(taskRepository.count()).isEqualTo(THREADS);
        // 書き込みを 1 つずつ処理していれば、並び順の数字は重ならない。重なっても id の小さい順になる
        List<Long> first = taskIdsInOrder(todo);
        assertThat(first).hasSize(THREADS);
        List<Task> tasks = taskRepository.findAll().stream()
                .sorted(Comparator.comparing(Task::getSortOrder).thenComparing(Task::getId)).toList();
        assertThat(first).containsExactlyElementsOf(tasks.stream().map(Task::getId).toList());
        for (int i = 0; i < 3; i++) {
            assertThat(taskIdsInOrder(todo)).containsExactlyElementsOf(first);
        }
    }

    // ---- DS-24・DS-25 ----

    @Test
    @DisplayName("DS-24 同じ札・同じ名前の列追加を同時に送ると、どちらも 201 で同じ列になり、列は 1 つ増えるだけ")
    void ds24ConcurrentColumnCreateWithSameKey() {
        for (int round = 0; round < ROUNDS; round++) {
            String key = UUID.randomUUID().toString();
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                calls.add(() -> postColumn(key, "確認待ち"));
            }

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(201);
            assertThat(results).extracting(DoubleSubmitConcurrencyTest::idOf).containsOnly(idOf(results.get(0)));
            assertThat(columnRepository.count()).isEqualTo(4);
            // 次のラウンドのために、足した列を消す
            assertThat(mvc.delete().uri("/api/columns/{id}", idOf(results.get(0)))).hasStatus(HttpStatus.NO_CONTENT);
        }
    }

    @Test
    @DisplayName("DS-25 ちがう札で同時に列追加を送ると、全部できて、右端の数字が重なっても並びは毎回同じ")
    void ds25ConcurrentColumnCreateWithDifferentKeys() {
        List<Supplier<MvcTestResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String name = "列" + i;
            calls.add(() -> postColumn(UUID.randomUUID().toString(), name));
        }

        List<MvcTestResult> results = runConcurrently(calls);

        assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(201);
        assertThat(results).extracting(DoubleSubmitConcurrencyTest::idOf).doesNotHaveDuplicates();
        assertThat(columnRepository.count()).isEqualTo(3 + THREADS);

        // 並びは（右端の数字, id）の順で決まる。何回取っても同じ
        List<BoardColumn> expected = columnRepository.findAll().stream()
                .sorted(Comparator.comparing(BoardColumn::getSortOrder).thenComparing(BoardColumn::getId)).toList();
        for (int i = 0; i < 3; i++) {
            String body = bodyOf(mvc.get().uri("/api/columns").exchange());
            List<Number> ids = JsonPath.read(body, "$[*].id");
            assertThat(ids).extracting(Number::longValue)
                    .containsExactlyElementsOf(expected.stream().map(BoardColumn::getId).toList());
        }
    }

    // ---- DS-30〜33 ----

    @Test
    @DisplayName("DS-30 import を同時に 2 本送ると、どちらも 204。片方のファイルの内容だけが残り、完了の列は 1 つ、内容は混ざらない")
    void ds30ConcurrentImports() {
        for (int round = 0; round < ROUNDS; round++) {
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            calls.add(() -> importFile(importJson("A")));
            calls.add(() -> importFile(importJson("B")));

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(204);
            // どちらか一方のファイルの内容だけが残る。A か B のどちらかと、完全に一致する（列もタスクも混ざらない）
            // どちらが残るかは、鍵を取れた順で決まる（あとから鍵を取ったほうが残る）。順番は外から分からないので、どちらでもよい
            assertThat(boardIsExactly("A") || boardIsExactly("B"))
                    .as("列とタスクが、A か B のどちらかのファイルの内容そのものになっている: %s", columnNames())
                    .isTrue();
            assertBoardIsConsistent();
            assertThat(columnRepository.count()).isEqualTo(3);
            assertThat(taskRepository.count()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("DS-30 import を同時に 8 本（内容は 2 種類）送っても、どれも 204 で、1 つのファイルの内容だけが残る")
    void ds30ManyConcurrentImports() {
        List<Supplier<MvcTestResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String prefix = i % 2 == 0 ? "A" : "B";
            calls.add(() -> importFile(importJson(prefix)));
        }

        List<MvcTestResult> results = runConcurrently(calls);

        assertThat(results).extracting(DoubleSubmitConcurrencyTest::statusOf).containsOnly(204);
        // 最後に鍵を取った 1 本のファイルの内容だけが残る（A か B のどちらかと完全に一致し、混ざらない）
        assertThat(boardIsExactly("A") || boardIsExactly("B")).isTrue();
        assertBoardIsConsistent();
    }

    @Test
    @DisplayName("DS-31 import とタスクの追加を同時に送ると、どちらも 500 にならず、追加は import の前なら消え、後なら残る")
    void ds31ImportAndCreateTask() {
        for (int round = 0; round < ROUNDS; round++) {
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            calls.add(() -> importFile(importJson("A")));
            // 列を指定しない追加（一番左の列に入る）。import で列が入れ替わっても 201 になるはず
            calls.add(() -> postTask(UUID.randomUUID().toString(), "{\"text\": \"追加\"}"));

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(statusOf(results.get(0))).isEqualTo(204);
            assertThat(statusOf(results.get(1))).isEqualTo(201);
            assertBoardIsConsistent();
            // import のファイルの内容は必ず入っている。追加は、残っていれば 1 件だけ（import に消されていれば 0 件）
            List<String> texts = taskRepository.findAll().stream().map(Task::getText).sorted().toList();
            assertThat(texts).contains("A-1", "A-2", "A-3");
            assertThat(texts.stream().filter("追加"::equals).count()).isLessThanOrEqualTo(1);
            assertThat(columnNames()).containsExactly("A-todo", "A-doing", "A-done");
            // 追加が残っているなら、今ある一番左の列に入っている
            taskRepository.findAll().stream().filter(task -> "追加".equals(task.getText())).forEach(task ->
                    assertThat(task.getColumnId()).isEqualTo(columnRepository.findAllByOrderBySortOrderAsc()
                            .get(0).getId()));
            // 次のラウンドのために基本の3列に戻す
            resetBoard();
        }
    }

    @Test
    @DisplayName("DS-32 import と列の削除を同時に送ると、どちらも 500 にならない（削除は 204 か 409）")
    void ds32ImportAndDeleteColumn() {
        for (int round = 0; round < ROUNDS; round++) {
            Long review = columnRepository.save(new BoardColumn("確認待ち", 3.0, false, false)).getId();
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            calls.add(() -> importFile(importJson("A")));
            calls.add(() -> mvc.delete().uri("/api/columns/{id}", review).exchange());

            List<MvcTestResult> results = runConcurrently(calls);

            assertThat(statusOf(results.get(0))).isEqualTo(204);
            assertThat(statusOf(results.get(1))).isIn(204, 409);
            assertThat(boardIsExactly("A")).isTrue();
            assertBoardIsConsistent();
            resetBoard();
        }
    }

    @Test
    @DisplayName("DS-33 ① 鍵をとる前の 400（タスク名が空・JSON が壊れている）のあと、今の列・タスクはそのままで、次の追加が成功する")
    void ds33RejectedBeforeLockDoesNotBlockNextWrite() {
        assertFailedImportLeavesBoardAndNextWriteSucceeds(List.of(
                // タスク名が空：Controller の入力チェック（@Valid）で断られる。トランザクションを始める前なので、鍵はとらない
                """
                {"version": 2, "columns": [
                  {"name": "X", "fixed": true, "done": true, "tasks": [{"text": "", "priority": "high"}]}
                ]}
                """,
                // JSON として読めない：JSON を読む時点で断られる。これも鍵をとる前
                "{\"version\": 2, \"columns\": ["));
    }

    @Test
    @DisplayName("DS-33 ② 鍵をとったあとの 400（基本の列が 3 つでない）のあと、今の列・タスクはそのままで、次の追加が成功する")
    void ds33RejectedAfterLockDoesNotBlockNextWrite() {
        assertFailedImportLeavesBoardAndNextWriteSucceeds(List.of(
                // 基本の列が 2 つ：入力チェックは通り、TaskService.importTasks の中（鍵をとったあと、消す前）で断られる
                // トランザクションが取り消されると、鍵も DB が自動で外す。外れなければ、次の追加が待たされて止まる
                """
                {"version": 2, "columns": [
                  {"name": "X", "fixed": true, "tasks": [{"text": "x", "priority": "high"}]},
                  {"name": "Y", "fixed": true, "done": true, "tasks": []}
                ]}
                """));
    }

    // 準備：今のタスクを 1 件入れる。実行：おかしなファイルを 1 つずつ読み込ませ、そのたびに 400 を確かめる
    // 確かめる：①今の列・タスクがそのまま残る、②鍵が残っていない（次の追加が、待たされずに 201 で返る）
    private void assertFailedImportLeavesBoardAndNextWriteSucceeds(List<String> brokenFiles) {
        taskRepository.save(new Task("今あるタスク", todo, TaskPriority.MEDIUM, null, 0.0));

        for (String broken : brokenFiles) {
            assertThat(importFile(broken)).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(taskRepository.findAll()).extracting(Task::getText).containsExactly("今あるタスク");
            assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");

            // 鍵が残っていれば、次の書き込みは待たされる。別スレッドで呼んで、一定時間内に 201 が返ることを確かめる
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            calls.add(() -> postTask(null, "{\"text\": \"次の追加\"}"));
            MvcTestResult next = runConcurrently(calls).get(0);
            assertThat(statusOf(next)).isEqualTo(201);
            assertThat(mvc.delete().uri("/api/tasks/{id}", idOf(next))).hasStatus(HttpStatus.NO_CONTENT);
        }
    }

    @Test
    @DisplayName("DS-33 import の失敗と、ほかの書き込みを同時に送り続けても、全部が終わる（鍵が残らない）")
    void ds33FailedImportsAndWritesDoNotDeadlock() {
        List<Supplier<MvcTestResult>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            if (i % 2 == 0) {
                calls.add(() -> importFile("{\"version\": 2, \"columns\": ["));
            } else {
                calls.add(() -> postTask(null, "{\"text\": \"追加\"}"));
            }
        }

        List<MvcTestResult> results = runConcurrently(calls);

        for (int i = 0; i < THREADS; i++) {
            assertThat(statusOf(results.get(i))).isEqualTo(i % 2 == 0 ? 400 : 201);
        }
        assertThat(taskRepository.count()).isEqualTo(THREADS / 2);
    }

    // ---- DS-35 ----

    // 基本の列 3 つに、足した列を EXTRA_COLUMNS 個はさんだ import ファイル。どの列にもタスクが 1 件ずつ入る
    private static String wideImportJson(String prefix) {
        StringBuilder columns = new StringBuilder();
        columns.append(column(prefix + "-todo", true, false, prefix + "-t"));
        for (int i = 0; i < EXTRA_COLUMNS; i++) {
            columns.append(',').append(column(prefix + "-c" + i, false, false, prefix + "-x" + i));
        }
        columns.append(',').append(column(prefix + "-doing", true, false, prefix + "-d"));
        columns.append(',').append(column(prefix + "-done", true, true, prefix + "-z"));
        return "{\"version\": 2, \"columns\": [" + columns + "]}";
    }

    private static String column(String name, boolean fixed, boolean done, String taskText) {
        return "{\"name\": \"" + name + "\", \"fixed\": " + fixed + ", \"done\": " + done
                + ", \"tasks\": [{\"text\": \"" + taskText + "\", \"priority\": \"high\"}]}";
    }

    // 書き出したファイルを「列の名前=その列のタスクの文章」の並びにする（ファイル同士を、中身だけで比べるため）
    // タスクの文章は、列ごとに「,」でつなぐ（タスクが空の列は「名前=」になる）
    private static List<String> exportedBoard(MvcTestResult result) {
        String body = bodyOf(result);
        List<String> names = JsonPath.read(body, "$.columns[*].name");
        List<String> board = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            List<String> texts = JsonPath.read(body, "$.columns[" + i + "].tasks[*].text");
            board.add(names.get(i) + "=" + String.join(",", texts));
        }
        return board;
    }

    // import ファイル（wideImportJson）を読み込んだあとの、書き出しの形
    private static List<String> expectedBoard(String prefix) {
        List<String> board = new ArrayList<>();
        board.add(prefix + "-todo=" + prefix + "-t");
        for (int i = 0; i < EXTRA_COLUMNS; i++) {
            board.add(prefix + "-c" + i + "=" + prefix + "-x" + i);
        }
        board.add(prefix + "-doing=" + prefix + "-d");
        board.add(prefix + "-done=" + prefix + "-z");
        return board;
    }

    @Test
    @DisplayName("DS-35 書き出しと import を同時に送ると、書き出したファイルは import の前か後の、どちらかの状態と完全に一致する")
    void ds35ExportDuringImportIsOneConsistentState() {
        for (int round = 0; round < ROUNDS * 4; round++) {
            // 準備：前の状態（P か Q）を読み込んでおく。毎ラウンド、入れ替える（前と後が必ずちがう中身になる）
            String before = round % 2 == 0 ? "P" : "Q";
            String after = round % 2 == 0 ? "Q" : "P";
            assertThat(importFile(wideImportJson(before))).hasStatus(HttpStatus.NO_CONTENT);

            // 実行：import 1 本と、書き出しのスレッド（THREADS - 1）本を、一斉に始める
            // 書き出しのスレッドは、import が終わるまで書き出しを繰り返す（1 回の書き出しは import より速いので、
            // 1 回だけだと import のコミットの前に終わってしまい、コミットの瞬間をまたぐ書き出しが出ないため）
            AtomicBoolean importDone = new AtomicBoolean(false);
            Queue<MvcTestResult> exports = new ConcurrentLinkedQueue<>();
            List<Supplier<MvcTestResult>> calls = new ArrayList<>();
            calls.add(() -> {
                MvcTestResult imported = importFile(wideImportJson(after));
                importDone.set(true);
                return imported;
            });
            for (int i = 1; i < THREADS; i++) {
                calls.add(() -> {
                    MvcTestResult export;
                    // import が終わったのを見たあとも 1 回は書き出す（後の状態の書き出しも、必ず確かめるため）
                    boolean last;
                    do {
                        last = importDone.get();
                        export = mvc.get().uri("/api/tasks/export").exchange();
                        exports.add(export);
                    } while (!last);
                    return export;
                });
            }
            List<MvcTestResult> results = runConcurrently(calls);

            // 確かめる：import は 204。書き出しは全部 200 で、前（before）か後（after）の状態と、列もタスクも完全に一致する
            // （列はあるのにタスクが空、前の列と後のタスクが混ざる、などがない）
            assertThat(statusOf(results.get(0))).isEqualTo(204);
            for (MvcTestResult export : exports) {
                assertThat(statusOf(export)).isEqualTo(200);
                assertThat(exportedBoard(export)).isIn(expectedBoard(before), expectedBoard(after));
            }
        }
    }
}
