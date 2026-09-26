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
