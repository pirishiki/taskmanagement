package com.taskmanagement.backend.task;

import com.taskmanagement.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// タスクの API（/api/tasks）のテスト
// アプリを丸ごと起動し、MockMvcTester で API を呼んで、返事を確かめる。DB は Testcontainers の使い捨ての PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TaskApiTest {

    // API を呼んで、返事を確かめる道具（ブラウザや curl の代わり）
    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private TaskRepository taskRepository;

    // テストごとに DB を空にする（前のテストで登録したタスクが、次のテストに混ざらないように）
    @BeforeEach
    void deleteAllTasks() {
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("status と priority を省略して登録すると、todo・medium になり、JSON は小文字で返る")
    void createWithDefaults() {
        // 準備と実行：タスク名だけを送って登録する
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "牛乳を買う"}
                        """)
                .exchange();

        // 確かめる：201 が返り、省略した項目が既定値になっている
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.text").isEqualTo("牛乳を買う");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("todo");
        assertThat(result).bodyJson().extractingPath("$.priority").isEqualTo("medium");
    }

    @Test
    @DisplayName("削除で並び順に隙間ができても、新しいタスクは列の一番下（最大値＋1）に入る")
    void newTaskGoesToBottomEvenAfterDelete() {
        // 準備：同じ列に 0・1・2 番の3件を作り、0 番を削除して、1・2 番だけが残る状態にする
        Task first = saveTask("1件目", TaskStatus.TODO, 0);
        saveTask("2件目", TaskStatus.TODO, 1);
        saveTask("3件目", TaskStatus.TODO, 2);
        assertThat(mvc.delete().uri("/api/tasks/{id}", first.getId())).hasStatus(HttpStatus.NO_CONTENT);

        // 実行：同じ列に新しいタスクを登録する
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "4件目", "status": "todo"}
                        """)
                .exchange();

        // 確かめる：件数（2）ではなく、最大値（2）＋1 の 3 番になる（件数で決めていたころは 2 番になり、3件目と重なっていた）
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(3.0);
    }

    // ここから下は、並び替え（move・reorder）のテスト
    // 絞り込み中を思い浮かべて、「画面に見えているカード」と「見えていないカード」を混ぜて準備する

    // 並び替えのテスト用に、列（status）と番号（sortOrder）を決めてタスクを1件作る
    private Task saveTask(String text, TaskStatus status, double sortOrder) {
        return taskRepository.save(new Task(text, status, TaskPriority.MEDIUM, null, sortOrder));
    }

    // その列のタスク名を、上から順に並べて返す（DB の本当の並び）
    private List<String> textsIn(TaskStatus status) {
        return taskRepository.findByStatusOrderBySortOrderAsc(status).stream().map(Task::getText).toList();
    }

    // PUT /api/tasks/{id}/move を呼ぶ。prevId が null なら、列の一番上に入れる頼みになる
    private MvcTestResult move(Long id, String status, Long prevId) {
        return mvc.put().uri("/api/tasks/{id}/move", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "%s", "prevId": %s}
                        """.formatted(status, prevId))
                .exchange();
    }

    @Test
    @DisplayName("move で A のすぐ下に入れると、見えていない B との真ん中の番号になり、ほかのカードの番号は変わらない")
    void moveGoesBetweenPrevAndHiddenNext() {
        // 準備：「やるべきこと」に A(0)・B(1)・C(2)。絞り込みで A と C だけが見えているとする
        // 「進行中」の D を、画面で A のすぐ下に落とす
        Task a = saveTask("A", TaskStatus.TODO, 0);
        saveTask("B", TaskStatus.TODO, 1);
        saveTask("C", TaskStatus.TODO, 2);
        Task d = saveTask("D", TaskStatus.DOING, 0);

        MvcTestResult result = move(d.getId(), "todo", a.getId());

        // 確かめる：D は A(0) と、DB で本当にすぐ下の B(1) の真ん中 0.5 に入る。見えていない B と C の順番はそのまま
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("todo");
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(0.5);
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("A", "D", "B", "C");
    }

    @Test
    @DisplayName("move で prevId を省くと列の一番上、一番下のカードを prevId にすると列の一番下に入る")
    void moveToTopAndBottom() {
        // 準備：A(0)・B(1)・C(2)
        saveTask("A", TaskStatus.TODO, 0);
        Task b = saveTask("B", TaskStatus.TODO, 1);
        Task c = saveTask("C", TaskStatus.TODO, 2);

        // 実行と確かめる：C を一番上へ（一番上の A より 1 小さい -1）
        assertThat(move(c.getId(), "todo", null)).bodyJson().extractingPath("$.sortOrder").isEqualTo(-1.0);
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("C", "A", "B");

        // 実行と確かめる：C を B（一番下）のすぐ下へ（B より 1 大きい 2）
        assertThat(move(c.getId(), "todo", b.getId())).bodyJson().extractingPath("$.sortOrder").isEqualTo(2.0);
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("move で番号のすき間がなくなっていたら、列を 0・1・2… と振り直してから真ん中に入れる")
    void moveRenumbersWhenNoGapIsLeft() {
        // 準備：A と B の番号を、間に小数を作れないほど近くする（Math.nextUp(1.0) は「1 のすぐ次の小数」）
        Task a = saveTask("A", TaskStatus.TODO, 1);
        saveTask("B", TaskStatus.TODO, Math.nextUp(1.0));
        Task d = saveTask("D", TaskStatus.DOING, 0);

        MvcTestResult result = move(d.getId(), "todo", a.getId());

        // 確かめる：A=0・B=1 に振り直されたあと、D はその真ん中の 0.5 に入る
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(0.5);
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("A", "D", "B");
    }

    @Test
    @DisplayName("move で、動かすカードか prevId のカードがなければ 404、prevId が別の列にあれば 400 になる")
    void moveWithWrongIdsIsError() {
        Task a = saveTask("A", TaskStatus.TODO, 0);
        Task b = saveTask("B", TaskStatus.DOING, 0);
        Task gone = saveTask("消すタスク", TaskStatus.TODO, 1);
        taskRepository.delete(gone);

        assertThat(move(gone.getId(), "todo", a.getId())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(move(b.getId(), "todo", gone.getId())).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult otherColumn = move(b.getId(), "done", a.getId()); // A は「やるべきこと」にある
        assertThat(otherColumn).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(otherColumn).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(otherColumn).bodyJson().extractingPath("$.detail").isEqualTo("上のカード（prevId）が、移動先の列にありません");
    }

    @Test
    @DisplayName("reorder は、送ったカードどうしで席（番号）を入れ替え、送らなかったカードの番号は変えない")
    void reorderSwapsSeatsOfSentTasksOnly() {
        // 準備：A(0)・B(1)・C(2)・D(3)。絞り込みで B と D だけが見えているとする
        saveTask("A", TaskStatus.TODO, 0);
        Task b = saveTask("B", TaskStatus.TODO, 1);
        saveTask("C", TaskStatus.TODO, 2);
        Task d = saveTask("D", TaskStatus.TODO, 3);

        // 実行：見えている2枚を D・B の順にする
        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "todo", "orderedIds": [%d, %d]}
                        """.formatted(d.getId(), b.getId()))
                .exchange();

        // 確かめる：B と D が座っていた席（1 と 3）を D・B の順に使う。A(0) と C(2) はそのまま
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.findById(d.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isEqualTo(1.0));
        assertThat(taskRepository.findById(b.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isEqualTo(3.0));
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("A", "D", "C", "B");
    }

    @Test
    @DisplayName("reorder に別の列のカードが混じっていると 400 になり、どのカードの番号も変わらない")
    void reorderWithOtherColumnIsBadRequest() {
        Task a = saveTask("A", TaskStatus.TODO, 0);
        Task b = saveTask("B", TaskStatus.DOING, 5);

        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "todo", "orderedIds": [%d, %d]}
                        """.formatted(b.getId(), a.getId()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(taskRepository.findById(a.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isZero());
        assertThat(taskRepository.findById(b.getId())).get()
                .satisfies(task -> assertThat(task.getStatus()).isEqualTo(TaskStatus.DOING));
    }

    @Test
    @DisplayName("削除すると 204 が返り、そのあと取得しようとすると 404 になる")
    void deleteThenNotFound() {
        // 準備
        Task task = saveTask("消すタスク", TaskStatus.TODO, 0);

        // 実行と確かめる：削除は 204、同じ id を取得すると 404
        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(taskRepository.existsById(task.getId())).isFalse();
    }

    // ここから下は、絞り込み（GET /api/tasks の keyword・priority・due）のテスト

    // 絞り込みのテスト用に、「やるべきこと」の列にタスクを1件作る
    private void saveTask(String text, TaskPriority priority, LocalDate dueDate) {
        taskRepository.save(new Task(text, TaskStatus.TODO, priority, dueDate, 0.0));
    }

    @Test
    @DisplayName("priority を2つ書くと、そのどちらかの優先度のタスクだけが返る")
    void filterByPriorities() {
        // 準備：高・中・低を1件ずつ
        saveTask("高のタスク", TaskPriority.HIGH, null);
        saveTask("中のタスク", TaskPriority.MEDIUM, null);
        saveTask("低のタスク", TaskPriority.LOW, null);

        // 実行：高と低で絞る
        MvcTestResult result = mvc.get().uri("/api/tasks")
                .param("priority", "high", "low")
                .exchange();

        // 確かめる：中は返らない
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("高のタスク", "低のタスク");
    }

    @Test
    @DisplayName("due は、overdue なら今日より前、week なら今日〜今日＋6日、none なら期限なしのタスクだけが返る")
    void filterByDue() {
        // 準備：境目の日付を1件ずつ（今日より前、今日、今日＋6日、今日＋7日、期限なし）
        LocalDate today = LocalDate.now();
        saveTask("昨日まで", TaskPriority.MEDIUM, today.minusDays(1));
        saveTask("今日まで", TaskPriority.MEDIUM, today);
        saveTask("6日後まで", TaskPriority.MEDIUM, today.plusDays(6));
        saveTask("7日後まで", TaskPriority.MEDIUM, today.plusDays(7));
        saveTask("期限なし", TaskPriority.MEDIUM, null);

        // 実行と確かめる：今日は「期限切れ」に入らない。今日＋6日は「7日以内」に入り、今日＋7日は入らない
        assertThat(mvc.get().uri("/api/tasks").param("due", "overdue"))
                .bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("昨日まで");
        assertThat(mvc.get().uri("/api/tasks").param("due", "week"))
                .bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("今日まで", "6日後まで");
        assertThat(mvc.get().uri("/api/tasks").param("due", "none"))
                .bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("期限なし");
    }

    @Test
    @DisplayName("keyword と priority を一緒に書くと、両方を満たすタスクだけが返る（AND）")
    void filterByKeywordAndPriority() {
        // 準備：キーワードだけ合う、優先度だけ合う、両方合う、の3件
        saveTask("牛乳を買う", TaskPriority.LOW, null);
        saveTask("パンを買う", TaskPriority.HIGH, null);
        saveTask("牛乳を飲む", TaskPriority.HIGH, null);

        // 実行：「牛乳」を含み、かつ高
        MvcTestResult result = mvc.get().uri("/api/tasks")
                .param("keyword", "牛乳")
                .param("priority", "high")
                .exchange();

        // 確かめる：片方だけ合うタスクは返らない（足し算の OR なら3件返ってしまう）
        assertThat(result).bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("牛乳を飲む");
    }

    @Test
    @DisplayName("keyword の % は、「何でもよい文字」ではなく、ただの文字として探す")
    void keywordTreatsPercentAsPlainText() {
        // 準備：「100%」を含むタスクと、含まないタスク
        saveTask("売上100%達成", TaskPriority.MEDIUM, null);
        saveTask("100円のパンを買う", TaskPriority.MEDIUM, null);

        // 実行と確かめる：% が「何でもよい」なら両方返ってしまう
        assertThat(mvc.get().uri("/api/tasks").param("keyword", "100%"))
                .bodyJson().extractingPath("$[*].text").asArray()
                .containsExactlyInAnyOrder("売上100%達成");
    }

    // ここから下は、エラーのテスト。返事はどれも ProblemDetail（application/problem+json）になる

    @Test
    @DisplayName("タスク名が空だと 400 になり、errors に理由が入る。DB には登録されない")
    void createWithBlankTextIsBadRequest() {
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "  "}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors.text").isEqualTo("タスク名を入力してください");
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("タスク名が 256 文字だと 400 になる（255 文字まで）")
    void createWithTooLongTextIsBadRequest() {
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "%s"}
                        """.formatted("あ".repeat(256)))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.text").isEqualTo("タスク名は255文字までにしてください");
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("status に決まっていない値（abc）を送ると 400 になる")
    void createWithUnknownStatusIsBadRequest() {
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "牛乳を買う", "status": "abc"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("絞り込みの priority・due に決まっていない値（urgent・someday）を書くと 400 になる")
    void filterWithUnknownValueIsBadRequest() {
        MvcTestResult unknownPriority = mvc.get().uri("/api/tasks").param("priority", "urgent").exchange();
        assertThat(unknownPriority).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownPriority).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        MvcTestResult unknownDue = mvc.get().uri("/api/tasks").param("due", "someday").exchange();
        assertThat(unknownDue).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownDue).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("並び替えで status を送らないと 400 になり、errors に理由が入る")
    void reorderWithoutStatusIsBadRequest() {
        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderedIds": []}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.status").isEqualTo("並べる列（status）を指定してください");
    }

    @Test
    @DisplayName("存在しない id を書き換えようとすると 404 になり、detail に理由が入る")
    void updateUnknownIdIsNotFound() {
        // 準備：一度作って消した id を使う（その id のタスクは確実に存在しない）
        Task task = saveTask("消すタスク", TaskStatus.TODO, 0);
        taskRepository.delete(task);

        MvcTestResult result = mvc.put().uri("/api/tasks/{id}", task.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "書き換え"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("id が " + task.getId() + " のタスクは見つかりません");
    }

    // ここから下は、書き出し（GET /api/tasks/export）と読み込み（POST /api/tasks/import）のテスト

    // POST /api/tasks/import に、json をファイルの中身として送る
    private MvcTestResult importFile(String json) {
        return mvc.post().uri("/api/tasks/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    @Test
    @DisplayName("書き出すと、絞り込みに関係なく全部のタスクが、id なしで列→並び順に返り、ファイル名の札が付く")
    void exportReturnsAllTasksAsFile() {
        // 準備：「やるべきこと」に A・B、「終わったこと」に C
        saveTask("A", TaskStatus.TODO, 0);
        saveTask("B", TaskStatus.TODO, 1);
        saveTask("C", TaskStatus.DONE, 0);

        // 実行：絞り込みの条件を付けても、書き出しは全部を返す
        MvcTestResult result = mvc.get().uri("/api/tasks/export").param("priority", "high").exchange();

        // 確かめる：/{id} ではなく書き出しの入口に届き、ファイルとして保存される札が付いている
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).headers().hasValue(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"tasks-" + LocalDate.now() + ".json\"");
        assertThat(result).bodyJson().extractingPath("$.version").isEqualTo(1);
        // 列は文字の順（done → todo）、その中は並び順の小さい順
        assertThat(result).bodyJson().extractingPath("$.tasks[*].text").asArray().containsExactly("C", "A", "B");
        assertThat(result).bodyJson().extractingPath("$.tasks[0]").asMap().doesNotContainKey("id");
    }

    @Test
    @DisplayName("読み込むと、今のタスクが全部消え、ファイルのタスクだけになる。列ごとの順番はファイルどおりで、番号は 0・1・2… になる")
    void importReplacesAllTasks() {
        // 準備：今あるタスク
        saveTask("古いタスク", TaskStatus.TODO, 0);

        // 実行：「やるべきこと」に P(5)・Q(0.5)・R(2)、「進行中」に D が入ったファイルを読み込む
        MvcTestResult result = importFile("""
                {"version": 1, "tasks": [
                  {"text": "P", "status": "todo", "priority": "low", "sortOrder": 5},
                  {"text": "Q", "status": "todo", "priority": "high", "sortOrder": 0.5},
                  {"text": "R", "status": "todo", "priority": "medium", "sortOrder": 2},
                  {"text": "D", "status": "doing", "priority": "high", "dueDate": "2026-10-01", "sortOrder": 0}
                ]}
                """);

        // 確かめる：古いタスクは消え、Q・R・P の順に 0・1・2 番になっている
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.count()).isEqualTo(4);
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("Q", "R", "P");
        assertThat(taskRepository.findByStatusOrderBySortOrderAsc(TaskStatus.TODO))
                .extracting(Task::getSortOrder).containsExactly(0.0, 1.0, 2.0);
        assertThat(taskRepository.findByStatusOrderBySortOrderAsc(TaskStatus.DOING)).singleElement()
                .satisfies(task -> {
                    assertThat(task.getText()).isEqualTo("D");
                    assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
                });
    }

    @Test
    @DisplayName("読み込むファイルに1件でもおかしいタスクがあると 400 になり、今のタスクは1件も消えない")
    void importWithBlankTextKeepsCurrentTasks() {
        saveTask("今あるタスク", TaskStatus.TODO, 0);

        // 2件目（tasks[1]）のタスク名が空
        MvcTestResult result = importFile("""
                {"version": 1, "tasks": [
                  {"text": "よいタスク", "status": "todo", "priority": "high", "sortOrder": 0},
                  {"text": "", "status": "todo", "priority": "high", "sortOrder": 1}
                ]}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors['tasks[1].text']").isEqualTo("タスク名を入力してください");
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("今あるタスク");
    }

    @Test
    @DisplayName("読み込むファイルの version が 1 でないとき、JSON として読めないときは 400 になり、今のタスクは消えない")
    void importWithUnknownVersionOrBrokenJsonIsBadRequest() {
        saveTask("今あるタスク", TaskStatus.TODO, 0);

        MvcTestResult unknownVersion = importFile("""
                {"version": 2, "tasks": []}
                """);
        assertThat(unknownVersion).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownVersion).bodyJson().extractingPath("$.detail")
                .isEqualTo("この版（version: 2）のファイルは読み込めません。読み込めるのは version が 1 のファイルです");

        assertThat(importFile("{\"version\": 1, \"tasks\": [")).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(textsIn(TaskStatus.TODO)).containsExactly("今あるタスク");
    }

    @Test
    @DisplayName("書き出したファイルをそのまま読み込むと、書き出したときと同じ中身に戻る")
    void exportThenImportRestoresTasks() {
        // 準備：書き出す前のタスク
        taskRepository.save(new Task("牛乳を買う", TaskStatus.TODO, TaskPriority.HIGH, LocalDate.of(2026, 10, 1), 0.0));
        saveTask("パンを買う", TaskStatus.TODO, 1);
        saveTask("本を読む", TaskStatus.DOING, 0);

        // 書き出して、その中身（JSON の文字）を取っておく
        MvcTestResult exported = mvc.get().uri("/api/tasks/export").exchange();
        String file = new String(exported.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        // 書き出したあとで、タスクを変えてしまう
        taskRepository.deleteAll();
        saveTask("あとで足したタスク", TaskStatus.DONE, 0);

        // 実行：取っておいたファイルを読み込む
        assertThat(importFile(file)).hasStatus(HttpStatus.NO_CONTENT);

        // 確かめる：書き出したときの中身に戻っている
        assertThat(textsIn(TaskStatus.TODO)).containsExactly("牛乳を買う", "パンを買う");
        assertThat(textsIn(TaskStatus.DOING)).containsExactly("本を読む");
        assertThat(textsIn(TaskStatus.DONE)).isEmpty();
        assertThat(taskRepository.findByStatusOrderBySortOrderAsc(TaskStatus.TODO).getFirst())
                .satisfies(task -> {
                    assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
                });
    }
}
