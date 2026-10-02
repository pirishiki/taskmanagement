package com.taskmanagement.backend.task;

import com.taskmanagement.backend.TestcontainersConfiguration;
import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
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

    @Autowired
    private BoardColumnRepository columnRepository;

    // テストで使う3つの列の番号（id）。テストごとに作り直すので、毎回変わる
    private Long todo;
    private Long doing;
    private Long done;

    // テストごとに DB を、「やるべきこと／進行中／終わったこと」の3列だけで、タスクが1件もない状態にする
    // （前のテストで登録したタスクや、読み込みで作り直した列が、次のテストに混ざらないように）
    // タスクが列を指しているので（外部キー）、タスクを先に消す
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        doing = columnRepository.save(new BoardColumn("進行中", 1.0, true, false)).getId();
        done = columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true)).getId();
    }

    @Test
    @DisplayName("columnId と priority を省略して登録すると、一番左の列・medium になり、JSON は小文字で返る")
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
        assertThat(result).bodyJson().extractingPath("$.columnId").isEqualTo(todo.intValue());
        assertThat(result).bodyJson().extractingPath("$.priority").isEqualTo("medium");
    }

    @Test
    @DisplayName("削除で並び順に隙間ができても、新しいタスクは列の一番下（最大値＋1）に入る")
    void newTaskGoesToBottomEvenAfterDelete() {
        // 準備：同じ列に 0・1・2 番の3件を作り、0 番を削除して、1・2 番だけが残る状態にする
        Task first = saveTask("1件目", todo, 0);
        saveTask("2件目", todo, 1);
        saveTask("3件目", todo, 2);
        assertThat(mvc.delete().uri("/api/tasks/{id}", first.getId())).hasStatus(HttpStatus.NO_CONTENT);

        // 実行：同じ列に新しいタスクを登録する
        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "4件目", "columnId": %d}
                        """.formatted(todo))
                .exchange();

        // 確かめる：件数（2）ではなく、最大値（2）＋1 の 3 番になる（件数で決めていたころは 2 番になり、3件目と重なっていた）
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(3.0);
    }

    @Test
    @DisplayName("PATCH で完了の列の番号を送ると、そのタスクは完了の列の一番下に移る")
    void patchMovesTaskToDoneColumn() {
        // 準備：「終わったこと」に1件、「やるべきこと」に完了させるタスク
        saveTask("前に終わったタスク", done, 0);
        Task task = saveTask("牛乳を買う", todo, 0);

        // 実行：完了ボタン（○）と同じ頼み方
        MvcTestResult result = mvc.patch().uri("/api/tasks/{id}", task.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"columnId": %d}
                        """.formatted(done))
                .exchange();

        // 確かめる：「終わったこと」の一番下に入っている
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.columnId").isEqualTo(done.intValue());
        assertThat(textsIn(done)).containsExactly("前に終わったタスク", "牛乳を買う");
    }

    @Test
    @DisplayName("PUT で columnId を省略すると、タスクは今の列の今の位置のまま、中身だけが変わる")
    void putWithoutColumnKeepsColumnAndPosition() {
        // 準備：「進行中」に2件。1件目は、別の端末で「やるべきこと」から動かしてきたつもりのタスク
        Task task = saveTask("牛乳を買う", doing, 0);
        saveTask("パンを買う", doing, 1);

        // 実行：画面のクイック編集の保存と同じ頼み方（列は送らない）
        MvcTestResult result = mvc.put().uri("/api/tasks/{id}", task.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "牛乳を2本買う", "priority": "high", "dueDate": null}
                        """)
                .exchange();

        // 確かめる：列も並び順も変わらず、タスク名と優先度だけが変わっている（イシュー #47 の #20）
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.columnId").isEqualTo(doing.intValue());
        assertThat(result).bodyJson().extractingPath("$.priority").isEqualTo("high");
        assertThat(textsIn(doing)).containsExactly("牛乳を2本買う", "パンを買う");
    }

    // ここから下は、並び替え（move・reorder）のテスト
    // 絞り込み中を思い浮かべて、「画面に見えているカード」と「見えていないカード」を混ぜて準備する

    // 列（columnId）と番号（sortOrder）を決めてタスクを1件作る
    private Task saveTask(String text, Long columnId, double sortOrder) {
        return taskRepository.save(new Task(text, columnId, TaskPriority.MEDIUM, null, sortOrder));
    }

    // その列のタスク名を、上から順に並べて返す（DB の本当の並び）
    private List<String> textsIn(Long columnId) {
        return taskRepository.findByColumnIdOrderBySortOrderAsc(columnId).stream().map(Task::getText).toList();
    }

    // PUT /api/tasks/{id}/move を呼ぶ。prevId が null なら、列の一番上に入れる頼みになる
    private MvcTestResult move(Long id, Long columnId, Long prevId) {
        return mvc.put().uri("/api/tasks/{id}/move", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"columnId": %s, "prevId": %s}
                        """.formatted(columnId, prevId))
                .exchange();
    }

    @Test
    @DisplayName("move で A のすぐ下に入れると、見えていない B との真ん中の番号になり、ほかのカードの番号は変わらない")
    void moveGoesBetweenPrevAndHiddenNext() {
        // 準備：「やるべきこと」に A(0)・B(1)・C(2)。絞り込みで A と C だけが見えているとする
        // 「進行中」の D を、画面で A のすぐ下に落とす
        Task a = saveTask("A", todo, 0);
        saveTask("B", todo, 1);
        saveTask("C", todo, 2);
        Task d = saveTask("D", doing, 0);

        MvcTestResult result = move(d.getId(), todo, a.getId());

        // 確かめる：D は A(0) と、DB で本当にすぐ下の B(1) の真ん中 0.5 に入る。見えていない B と C の順番はそのまま
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.columnId").isEqualTo(todo.intValue());
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(0.5);
        assertThat(textsIn(todo)).containsExactly("A", "D", "B", "C");
    }

    @Test
    @DisplayName("move で prevId を省くと列の一番上、一番下のカードを prevId にすると列の一番下に入る")
    void moveToTopAndBottom() {
        // 準備：A(0)・B(1)・C(2)
        saveTask("A", todo, 0);
        Task b = saveTask("B", todo, 1);
        Task c = saveTask("C", todo, 2);

        // 実行と確かめる：C を一番上へ（一番上の A より 1 小さい -1）
        assertThat(move(c.getId(), todo, null)).bodyJson().extractingPath("$.sortOrder").isEqualTo(-1.0);
        assertThat(textsIn(todo)).containsExactly("C", "A", "B");

        // 実行と確かめる：C を B（一番下）のすぐ下へ（B より 1 大きい 2）
        assertThat(move(c.getId(), todo, b.getId())).bodyJson().extractingPath("$.sortOrder").isEqualTo(2.0);
        assertThat(textsIn(todo)).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("move で番号のすき間がなくなっていたら、列を 0・1・2… と振り直してから真ん中に入れる")
    void moveRenumbersWhenNoGapIsLeft() {
        // 準備：A と B の番号を、間に小数を作れないほど近くする（Math.nextUp(1.0) は「1 のすぐ次の小数」）
        Task a = saveTask("A", todo, 1);
        saveTask("B", todo, Math.nextUp(1.0));
        Task d = saveTask("D", doing, 0);

        MvcTestResult result = move(d.getId(), todo, a.getId());

        // 確かめる：A=0・B=1 に振り直されたあと、D はその真ん中の 0.5 に入る
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(0.5);
        assertThat(textsIn(todo)).containsExactly("A", "D", "B");
    }

    @Test
    @DisplayName("move で、動かすカードか prevId のカードがなければ 404、prevId が別の列にあれば 400 になる")
    void moveWithWrongIdsIsError() {
        Task a = saveTask("A", todo, 0);
        Task b = saveTask("B", doing, 0);
        Task gone = saveTask("消すタスク", todo, 1);
        taskRepository.delete(gone);

        assertThat(move(gone.getId(), todo, a.getId())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(move(b.getId(), todo, gone.getId())).hasStatus(HttpStatus.NOT_FOUND);

        MvcTestResult otherColumn = move(b.getId(), done, a.getId()); // A は「やるべきこと」にある
        assertThat(otherColumn).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(otherColumn).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(otherColumn).bodyJson().extractingPath("$.detail").isEqualTo("上のカード（prevId）が、移動先の列にありません");
    }

    @Test
    @DisplayName("reorder は、送ったカードどうしで席（番号）を入れ替え、送らなかったカードの番号は変えない")
    void reorderSwapsSeatsOfSentTasksOnly() {
        // 準備：A(0)・B(1)・C(2)・D(3)。絞り込みで B と D だけが見えているとする
        saveTask("A", todo, 0);
        Task b = saveTask("B", todo, 1);
        saveTask("C", todo, 2);
        Task d = saveTask("D", todo, 3);

        // 実行：見えている2枚を D・B の順にする
        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"columnId": %d, "orderedIds": [%d, %d]}
                        """.formatted(todo, d.getId(), b.getId()))
                .exchange();

        // 確かめる：B と D が座っていた席（1 と 3）を D・B の順に使う。A(0) と C(2) はそのまま
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.findById(d.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isEqualTo(1.0));
        assertThat(taskRepository.findById(b.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isEqualTo(3.0));
        assertThat(textsIn(todo)).containsExactly("A", "D", "C", "B");
    }

    @Test
    @DisplayName("reorder に別の列のカードが混じっていると 400 になり、どのカードの番号も変わらない")
    void reorderWithOtherColumnIsBadRequest() {
        Task a = saveTask("A", todo, 0);
        Task b = saveTask("B", doing, 5);

        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"columnId": %d, "orderedIds": [%d, %d]}
                        """.formatted(todo, b.getId(), a.getId()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(taskRepository.findById(a.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isZero());
        assertThat(taskRepository.findById(b.getId())).get()
                .satisfies(task -> assertThat(task.getColumnId()).isEqualTo(doing));
    }

    @Test
    @DisplayName("削除すると 204 が返り、そのあと取得しようとすると 404 になる")
    void deleteThenNotFound() {
        // 準備
        Task task = saveTask("消すタスク", todo, 0);

        // 実行と確かめる：削除は 204、同じ id を取得すると 404
        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(taskRepository.existsById(task.getId())).isFalse();
    }

    // ここから下は、絞り込み（GET /api/tasks の keyword・priority・due）のテスト

    // 絞り込みのテスト用に、「やるべきこと」の列にタスクを1件作る
    private void saveTask(String text, TaskPriority priority, LocalDate dueDate) {
        taskRepository.save(new Task(text, todo, priority, dueDate, 0.0));
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
    @DisplayName("ない列の番号（columnId）を送ると 400 になり、detail に理由が入る。DB には登録されない")
    void createWithUnknownColumnIsBadRequest() {
        // 準備：一度作って消した列の番号を使う（その番号の列は確実に存在しない）
        BoardColumn gone = columnRepository.save(new BoardColumn("消す列", 9.0, false, false));
        columnRepository.delete(gone);

        MvcTestResult result = mvc.post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "牛乳を買う", "columnId": %d}
                        """.formatted(gone.getId()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("id が " + gone.getId() + " の列はありません");
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
    @DisplayName("並び替えで columnId を送らないと 400 になり、errors に理由が入る")
    void reorderWithoutColumnIsBadRequest() {
        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderedIds": []}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors.columnId").isEqualTo("並べる列（columnId）を指定してください");
    }

    @Test
    @DisplayName("存在しない id を書き換えようとすると 404 になり、detail に理由が入る")
    void updateUnknownIdIsNotFound() {
        // 準備：一度作って消した id を使う（その id のタスクは確実に存在しない）
        Task task = saveTask("消すタスク", todo, 0);
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

    // 今の列の名前を、左から順に並べて返す
    private List<String> columnNames() {
        return columnRepository.findAllByOrderBySortOrderAsc().stream().map(BoardColumn::getName).toList();
    }

    // 名前で列を探して、その列のタスク名を上から順に返す（読み込むと列の番号が変わるので、名前で探す）
    private List<String> textsInColumnNamed(String name) {
        BoardColumn column = columnRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow();
        return textsIn(column.getId());
    }

    @Test
    @DisplayName("書き出すと、絞り込みに関係なく全部の列とタスクが、id なしで左から・上から順に返り、ファイル名の札が付く")
    void exportReturnsAllColumnsAndTasksAsFile() {
        // 準備：「やるべきこと」に A・B、「終わったこと」に C
        saveTask("A", todo, 0);
        saveTask("B", todo, 1);
        saveTask("C", done, 0);

        // 実行：絞り込みの条件を付けても、書き出しは全部を返す
        MvcTestResult result = mvc.get().uri("/api/tasks/export").param("priority", "high").exchange();

        // 確かめる：/{id} ではなく書き出しの入口に届き、ファイルとして保存される札が付いている
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).headers().hasValue(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"tasks-" + LocalDate.now() + ".json\"");
        assertThat(result).bodyJson().extractingPath("$.version").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.columns[*].name").asArray()
                .containsExactly("やるべきこと", "進行中", "終わったこと");
        assertThat(result).bodyJson().extractingPath("$.columns[*].fixed").asArray()
                .containsExactly(true, true, true);
        assertThat(result).bodyJson().extractingPath("$.columns[*].done").asArray()
                .containsExactly(false, false, true);
        assertThat(result).bodyJson().extractingPath("$.columns[0].tasks[*].text").asArray().containsExactly("A", "B");
        assertThat(result).bodyJson().extractingPath("$.columns[2].tasks[*].text").asArray().containsExactly("C");
        assertThat(result).bodyJson().extractingPath("$.columns[0].tasks[0]").asMap().doesNotContainKey("id");
        // 古い版の項目（tasks）は書き出さない
        assertThat(result).bodyJson().extractingPath("$").asMap().doesNotContainKey("tasks");
    }

    @Test
    @DisplayName("読み込むと、今の列とタスクが全部消え、ファイルの列とタスクだけになる。順番はファイルに書いた順")
    void importReplacesAllColumnsAndTasks() {
        // 準備：今あるタスク
        saveTask("古いタスク", todo, 0);

        // 実行：基本の列「アイデア」「作業中」「完了」と、足した列「確認待ち」が入ったファイルを読み込む
        MvcTestResult result = importFile("""
                {"version": 2, "columns": [
                  {"name": "アイデア", "fixed": true, "tasks": [
                    {"text": "Q", "priority": "high"},
                    {"text": "R", "priority": "medium"},
                    {"text": "P", "priority": "low"}
                  ]},
                  {"name": "作業中", "fixed": true, "tasks": [
                    {"text": "D", "priority": "high", "dueDate": "2026-10-01"}
                  ]},
                  {"name": "確認待ち", "tasks": []},
                  {"name": "完了", "fixed": true, "done": true, "tasks": []}
                ]}
                """);

        // 確かめる：古い列とタスクは消え、ファイルどおりになっている。番号は 0・1・2…
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(columnNames()).containsExactly("アイデア", "作業中", "確認待ち", "完了");
        assertThat(columnRepository.findAllByOrderBySortOrderAsc())
                .extracting(BoardColumn::isFixed).containsExactly(true, true, false, true);
        assertThat(columnRepository.findByDoneTrue()).get()
                .satisfies(column -> assertThat(column.getName()).isEqualTo("完了"));
        assertThat(taskRepository.count()).isEqualTo(4);
        assertThat(textsInColumnNamed("アイデア")).containsExactly("Q", "R", "P");
        BoardColumn working = columnRepository.findAllByOrderBySortOrderAsc().get(1);
        assertThat(taskRepository.findByColumnIdOrderBySortOrderAsc(working.getId())).singleElement()
                .satisfies(task -> {
                    assertThat(task.getText()).isEqualTo("D");
                    assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
                    assertThat(task.getSortOrder()).isZero();
                });
    }

    @Test
    @DisplayName("古い版（version 1）のファイルも読み込め、todo・doing・done が3つの列に、並び順どおりに入る")
    void importVersion1File() {
        saveTask("古いタスク", todo, 0);

        // 実行：「やるべきこと」に P(5)・Q(0.5)・R(2)、「進行中」に D が入った、#36 の形のファイルを読み込む
        MvcTestResult result = importFile("""
                {"version": 1, "tasks": [
                  {"text": "P", "status": "todo", "priority": "low", "sortOrder": 5},
                  {"text": "Q", "status": "todo", "priority": "high", "sortOrder": 0.5},
                  {"text": "R", "status": "todo", "priority": "medium", "sortOrder": 2},
                  {"text": "D", "status": "doing", "priority": "high", "sortOrder": 0}
                ]}
                """);

        // 確かめる：3つの基本の列が作られ、「終わったこと」が完了の列。Q・R・P の順になっている
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");
        assertThat(columnRepository.findAll()).allSatisfy(column -> assertThat(column.isFixed()).isTrue());
        assertThat(columnRepository.findByDoneTrue()).get()
                .satisfies(column -> assertThat(column.getName()).isEqualTo("終わったこと"));
        assertThat(textsInColumnNamed("やるべきこと")).containsExactly("Q", "R", "P");
        assertThat(textsInColumnNamed("進行中")).containsExactly("D");
        assertThat(textsInColumnNamed("終わったこと")).isEmpty();
    }

    @Test
    @DisplayName("読み込むファイルに1件でもおかしいタスクがあると 400 になり、今のタスクは1件も消えない")
    void importWithBlankTextKeepsCurrentTasks() {
        saveTask("今あるタスク", todo, 0);

        // 1つ目の列の2件目（columns[0].tasks[1]）のタスク名が空
        MvcTestResult result = importFile("""
                {"version": 2, "columns": [
                  {"name": "やるべきこと", "done": true, "tasks": [
                    {"text": "よいタスク", "priority": "high"},
                    {"text": "", "priority": "high"}
                  ]}
                ]}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors['columns[0].tasks[1].text']")
                .isEqualTo("タスク名を入力してください");
        assertThat(textsIn(todo)).containsExactly("今あるタスク");
    }

    @Test
    @DisplayName("読み込むファイルの基本の列が3つでない、完了の列が1つでない、完了の列が基本の列でない、のどれかなら 400 になり、今の列とタスクは消えない")
    void importWithWrongFixedOrDoneColumnsIsBadRequest() {
        saveTask("今あるタスク", todo, 0);

        // 基本の列が2つしかない
        MvcTestResult twoFixed = importFile("""
                {"version": 2, "columns": [
                  {"name": "A", "fixed": true, "tasks": []},
                  {"name": "B", "fixed": true, "done": true, "tasks": []}
                ]}
                """);
        assertThat(twoFixed).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(twoFixed).bodyJson().extractingPath("$.detail")
                .isEqualTo("基本の列（fixed が true の列）は、ちょうど3つにしてください（今は 2 個）");

        // 完了の列がない
        MvcTestResult noDone = importFile("""
                {"version": 2, "columns": [
                  {"name": "A", "fixed": true, "tasks": []},
                  {"name": "B", "fixed": true, "tasks": []},
                  {"name": "C", "fixed": true, "tasks": []}
                ]}
                """);
        assertThat(noDone).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noDone).bodyJson().extractingPath("$.detail")
                .isEqualTo("完了の列（done が true の列）は、ちょうど1つにしてください（今は 0 個）");

        // 完了の列が、足した列（基本の列ではない）
        MvcTestResult doneNotFixed = importFile("""
                {"version": 2, "columns": [
                  {"name": "A", "fixed": true, "tasks": []},
                  {"name": "B", "fixed": true, "tasks": []},
                  {"name": "C", "fixed": true, "tasks": []},
                  {"name": "確認待ち", "done": true, "tasks": []}
                ]}
                """);
        assertThat(doneNotFixed).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(doneNotFixed).bodyJson().extractingPath("$.detail")
                .isEqualTo("完了の列は、基本の列（fixed が true の列）にしてください");

        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");
        assertThat(textsIn(todo)).containsExactly("今あるタスク");
    }

    @Test
    @DisplayName("読み込むファイルの version が 1・2 でないとき、JSON として読めないときは 400 になり、今のタスクは消えない")
    void importWithUnknownVersionOrBrokenJsonIsBadRequest() {
        saveTask("今あるタスク", todo, 0);

        MvcTestResult unknownVersion = importFile("""
                {"version": 3, "columns": []}
                """);
        assertThat(unknownVersion).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownVersion).bodyJson().extractingPath("$.detail")
                .isEqualTo("この版（version: 3）のファイルは読み込めません。読み込めるのは version が 1 か 2 のファイルです");

        assertThat(importFile("{\"version\": 2, \"columns\": [")).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(textsIn(todo)).containsExactly("今あるタスク");
    }

    @Test
    @DisplayName("書き出したファイルをそのまま読み込むと、書き出したときと同じ中身に戻る")
    void exportThenImportRestoresBoard() {
        // 準備：書き出す前の列とタスク（列「確認待ち」を足して、そこにもタスクを入れておく）
        Long review = columnRepository.save(new BoardColumn("確認待ち", 3.0, false, false)).getId();
        taskRepository.save(new Task("牛乳を買う", todo, TaskPriority.HIGH, LocalDate.of(2026, 10, 1), 0.0));
        saveTask("パンを買う", todo, 1);
        saveTask("本を読む", doing, 0);
        saveTask("見直す", review, 0);

        // 書き出して、その中身（JSON の文字）を取っておく
        MvcTestResult exported = mvc.get().uri("/api/tasks/export").exchange();
        String file = new String(exported.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        // 書き出したあとで、列とタスクを変えてしまう
        resetBoard();
        saveTask("あとで足したタスク", done, 0);

        // 実行：取っておいたファイルを読み込む
        assertThat(importFile(file)).hasStatus(HttpStatus.NO_CONTENT);

        // 確かめる：書き出したときの中身に戻っている
        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと", "確認待ち");
        assertThat(columnRepository.findAllByOrderBySortOrderAsc())
                .extracting(BoardColumn::isFixed).containsExactly(true, true, true, false);
        assertThat(columnRepository.findByDoneTrue()).get()
                .satisfies(column -> assertThat(column.getName()).isEqualTo("終わったこと"));
        assertThat(textsInColumnNamed("やるべきこと")).containsExactly("牛乳を買う", "パンを買う");
        assertThat(textsInColumnNamed("進行中")).containsExactly("本を読む");
        assertThat(textsInColumnNamed("終わったこと")).isEmpty();
        assertThat(textsInColumnNamed("確認待ち")).containsExactly("見直す");
        assertThat(taskRepository.findAll()).filteredOn(task -> "牛乳を買う".equals(task.getText())).singleElement()
                .satisfies(task -> {
                    assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
                });
    }
}
