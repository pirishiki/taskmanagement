package com.taskmanagement.backend.column;

import com.taskmanagement.backend.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 列の API（/api/columns）のテスト
// アプリを丸ごと起動し、MockMvcTester で API を呼んで、返事を確かめる。DB は Testcontainers の使い捨ての PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BoardColumnApiTest {

    // API を呼んで、返事を確かめる道具（ブラウザや curl の代わり）
    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private BoardColumnRepository columnRepository;

    @Autowired
    private TaskRepository taskRepository;

    // テストで使う3つの列の番号（id）。テストごとに作り直すので、毎回変わる
    private Long todo;
    private Long doing;
    private Long done;

    // テストごとに DB を、「やるべきこと／進行中／終わったこと」の3列だけで、タスクが1件もない状態にする
    // タスクが列を指しているので（外部キー）、タスクを先に消す
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        doing = columnRepository.save(new BoardColumn("進行中", 1.0, true, false)).getId();
        done = columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true)).getId();
    }

    // 今の列の名前を、左から順に並べて返す
    private List<String> columnNames() {
        return columnRepository.findAllByOrderBySortOrderAsc().stream().map(BoardColumn::getName).toList();
    }

    // その列にタスクを1件入れる
    private void saveTaskIn(Long columnId) {
        taskRepository.save(new Task("タスク", columnId, TaskPriority.MEDIUM, null, 0.0));
    }

    // あとから足した列（基本の列ではない列）を、右端に1つ作る
    private Long saveCustomColumn(String name) {
        return columnRepository.save(new BoardColumn(name, 3.0, false, false)).getId();
    }

    @Test
    @DisplayName("一覧は、全部の列を左から順に、基本の列・完了の列の印つきで返す")
    void listReturnsColumnsFromLeft() {
        MvcTestResult result = mvc.get().uri("/api/columns").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[*].name").asArray()
                .containsExactly("やるべきこと", "進行中", "終わったこと");
        assertThat(result).bodyJson().extractingPath("$[*].fixed").asArray().containsExactly(true, true, true);
        assertThat(result).bodyJson().extractingPath("$[*].done").asArray().containsExactly(false, false, true);
    }

    @Test
    @DisplayName("列を作ると 201 が返り、名前の前後の空白を取って、基本の列・完了の列の印なしで右端に入る")
    void createAddsColumnToRight() {
        MvcTestResult result = mvc.post().uri("/api/columns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "  確認待ち  "}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("確認待ち");
        assertThat(result).bodyJson().extractingPath("$.fixed").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.done").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.sortOrder").isEqualTo(3.0);
        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと", "確認待ち");
    }

    @Test
    @DisplayName("列の名前が空だと 400 になり、errors に理由が入る。列は増えない")
    void createWithBlankNameIsBadRequest() {
        MvcTestResult result = mvc.post().uri("/api/columns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": " "}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors.name").isEqualTo("列の名前を入力してください");
        assertThat(columnRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("列の名前を変えられる。ない列なら 404")
    void renameColumn() {
        MvcTestResult result = mvc.put().uri("/api/columns/{id}", doing)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "作業中"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("作業中");
        assertThat(columnNames()).containsExactly("やるべきこと", "作業中", "終わったこと");

        // 準備：一度作って消した列の番号を使う（その番号の列は確実に存在しない）
        BoardColumn gone = columnRepository.save(new BoardColumn("消す列", 9.0, false, false));
        columnRepository.delete(gone);
        MvcTestResult notFound = mvc.put().uri("/api/columns/{id}", gone.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "作業中"}
                        """)
                .exchange();
        assertThat(notFound).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(notFound).bodyJson().extractingPath("$.detail")
                .isEqualTo("id が " + gone.getId() + " の列は見つかりません");
    }

    @Test
    @DisplayName("並べ替えると、送った順に左から並ぶ")
    void reorderColumns() {
        MvcTestResult result = mvc.put().uri("/api/columns/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderedIds": [%d, %d, %d]}
                        """.formatted(done, todo, doing))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[*].name").asArray()
                .containsExactly("終わったこと", "やるべきこと", "進行中");
        assertThat(columnNames()).containsExactly("終わったこと", "やるべきこと", "進行中");
    }

    @Test
    @DisplayName("並べ替えで、足りない列・同じ列が2回ある、のどちらかなら 400 になり、並びは変わらない")
    void reorderWithWrongIdsIsBadRequest() {
        MvcTestResult missing = mvc.put().uri("/api/columns/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderedIds": [%d, %d]}
                        """.formatted(done, todo))
                .exchange();
        assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(missing).bodyJson().extractingPath("$.detail").isEqualTo("全部の列の ID を、1回ずつ並べて送ってください");

        MvcTestResult duplicated = mvc.put().uri("/api/columns/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderedIds": [%d, %d, %d, %d]}
                        """.formatted(done, todo, doing, todo))
                .exchange();
        assertThat(duplicated).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");
    }

    @Test
    @DisplayName("あとから足した列は、タスクがいなければ消せる（204）")
    void deleteEmptyCustomColumn() {
        Long review = saveCustomColumn("確認待ち");

        assertThat(mvc.delete().uri("/api/columns/{id}", review)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");
    }

    @Test
    @DisplayName("タスクのいる列を消そうとすると 409 になり、列もタスクも残る")
    void deleteColumnWithTasksIsConflict() {
        Long review = saveCustomColumn("確認待ち");
        saveTaskIn(review);

        MvcTestResult result = mvc.delete().uri("/api/columns/{id}", review).exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("タスクが入っている列は消せません。先にタスクをほかの列へ移すか、消してください");
        assertThat(columnRepository.existsById(review)).isTrue();
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("基本の列（やるべきこと・進行中・終わったこと）は、タスクがいなくても消せない（409）")
    void deleteFixedColumnIsConflict() {
        for (Long fixed : List.of(todo, doing, done)) {
            MvcTestResult result = mvc.delete().uri("/api/columns/{id}", fixed).exchange();

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.detail")
                    .isEqualTo("基本の列（やるべきこと・進行中・終わったこと）は消せません");
        }
        assertThat(columnNames()).containsExactly("やるべきこと", "進行中", "終わったこと");
    }

    @Test
    @DisplayName("完了の印の付け替え（PUT /api/columns/{id}/done）はできない。完了の列は「終わったこと」のまま")
    void markAsDoneIsNotAvailable() {
        // URL がないので、Spring がエラーを返す（このアプリでは 404 か 405）
        MvcTestResult result = mvc.put().uri("/api/columns/{id}/done", doing).exchange();

        assertThat(result.getResponse().getStatus()).isIn(404, 405);
        assertThat(columnRepository.findByDoneTrue()).get()
                .satisfies(column -> assertThat(column.getId()).isEqualTo(done));
    }

    @Test
    @DisplayName("ない列を消そうとすると 404 になる")
    void deleteUnknownColumnIsNotFound() {
        BoardColumn gone = columnRepository.save(new BoardColumn("消す列", 9.0, false, false));
        columnRepository.delete(gone);

        assertThat(mvc.delete().uri("/api/columns/{id}", gone.getId())).hasStatus(HttpStatus.NOT_FOUND);
    }
}
