package com.taskmanagement.backend.task;

import com.taskmanagement.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;

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
        Task first = taskRepository.save(new Task("1件目", TaskStatus.TODO, TaskPriority.MEDIUM, null, 0));
        taskRepository.save(new Task("2件目", TaskStatus.TODO, TaskPriority.MEDIUM, null, 1));
        taskRepository.save(new Task("3件目", TaskStatus.TODO, TaskPriority.MEDIUM, null, 2));
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
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(3);
    }

    @Test
    @DisplayName("並び替えると、送った ID の順番どおりに 0・1・2 番が振り直され、列も移る")
    void reorderRenumbersAndMovesColumn() {
        // 準備：「やるべきこと」に2件、「進行中」に1件
        Task todoA = taskRepository.save(new Task("A", TaskStatus.TODO, TaskPriority.MEDIUM, null, 0));
        Task todoB = taskRepository.save(new Task("B", TaskStatus.TODO, TaskPriority.MEDIUM, null, 1));
        Task doingC = taskRepository.save(new Task("C", TaskStatus.DOING, TaskPriority.MEDIUM, null, 0));

        // 実行：「進行中」の列を C・B・A の順にする（B と A は「やるべきこと」から移ってくる）
        MvcTestResult result = mvc.put().uri("/api/tasks/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "doing", "orderedIds": [%d, %d, %d]}
                        """.formatted(doingC.getId(), todoB.getId(), todoA.getId()))
                .exchange();

        // 確かめる：204 が返り、DB の中で3件とも「進行中」になって、C・B・A の順に 0・1・2 番になっている
        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.findById(doingC.getId())).get()
                .satisfies(task -> assertThat(task.getSortOrder()).isZero());
        assertThat(taskRepository.findById(todoB.getId())).get()
                .satisfies(task -> {
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.DOING);
                    assertThat(task.getSortOrder()).isEqualTo(1);
                });
        assertThat(taskRepository.findById(todoA.getId())).get()
                .satisfies(task -> {
                    assertThat(task.getStatus()).isEqualTo(TaskStatus.DOING);
                    assertThat(task.getSortOrder()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("削除すると 204 が返り、そのあと取得しようとすると 404 になる")
    void deleteThenNotFound() {
        // 準備
        Task task = taskRepository.save(new Task("消すタスク", TaskStatus.TODO, TaskPriority.LOW, null, 0));

        // 実行と確かめる：削除は 204、同じ id を取得すると 404
        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(taskRepository.existsById(task.getId())).isFalse();
    }

    // ここから下は、絞り込み（GET /api/tasks の keyword・priority・due）のテスト

    // 絞り込みのテスト用に、「やるべきこと」の列にタスクを1件作る
    private void saveTask(String text, TaskPriority priority, LocalDate dueDate) {
        taskRepository.save(new Task(text, TaskStatus.TODO, priority, dueDate, 0));
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
        Task task = taskRepository.save(new Task("消すタスク", TaskStatus.TODO, TaskPriority.LOW, null, 0));
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
}
