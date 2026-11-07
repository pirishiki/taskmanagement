package com.taskmanagement.backend.task;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 二重送信の設計書（docs/二重送信の設計書.md）5.1 節の、タスクの削除（DS-01〜03）と、番号札つきのタスク追加（DS-10〜15・18）のテスト
// 札は、ヘッダー Idempotency-Key（UUID）。札がないときは今までどおり。アプリを丸ごと起動し、DB は Testcontainers の使い捨ての PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DoubleSubmitTaskApiTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    // 掃除のテスト用に入れる、ダミーの指紋（SHA-256 の 16 進と同じ 64 文字）
    private static final String DUMMY_FINGERPRINT = "0".repeat(64);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BoardColumnRepository columnRepository;

    // 番号札の表（request_tokens）に直接さわるための道具
    @Autowired
    private JdbcTemplate jdbc;

    private Long todo;
    private Long doing;

    // テストごとに、3列だけ・タスクが 1 件もない状態にする
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        doing = columnRepository.save(new BoardColumn("進行中", 1.0, true, false)).getId();
        columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true));
    }

    // 札つきで POST /api/tasks を呼ぶ。key が null なら札なし
    private MvcTestResult postTask(String key, String json) {
        var request = mvc.post().uri("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header(KEY_HEADER, key);
        }
        return request.exchange();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Long idOf(MvcTestResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Task saveTask(String text, Long columnId, double sortOrder) {
        return taskRepository.save(new Task(text, columnId, TaskPriority.MEDIUM, null, sortOrder));
    }

    private int tokenRows(String token) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM request_tokens WHERE token = ?::uuid",
                Integer.class, token);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("DS-01 タスクを削除する（ある番号）と 204 になり、タスクが消える")
    void ds01DeleteExistingTask() {
        Task task = saveTask("消すタスク", todo, 0);

        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.existsById(task.getId())).isFalse();
    }

    @Test
    @DisplayName("DS-02 同じタスクをもう一度削除しても 204（404 でない）")
    void ds02DeleteTwiceIsNoContent() {
        Task task = saveTask("消すタスク", todo, 0);

        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.delete().uri("/api/tasks/{id}", task.getId())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("DS-03 一度もなかった番号のタスクを削除しても 204")
    void ds03DeleteNeverExistedTask() {
        // 一度作って消した番号より大きい番号は、一度も使われていない
        Task task = saveTask("作って消す", todo, 0);
        taskRepository.delete(task);
        long neverUsed = task.getId() + 1000;

        assertThat(mvc.delete().uri("/api/tasks/{id}", neverUsed)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DS-10 札つきでタスクを追加すると 201 になり、タスクが 1 件できて、札が記録される")
    void ds10CreateWithKey() {
        String key = newKey();

        MvcTestResult result = postTask(key, """
                {"text": "牛乳を買う"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.text").isEqualTo("牛乳を買う");
        assertThat(taskRepository.count()).isEqualTo(1);
        // 札は表 request_tokens に、作ったタスクの番号つきで残る
        assertThat(tokenRows(key)).isEqualTo(1);
        Long resourceId = jdbc.queryForObject("SELECT resource_id FROM request_tokens WHERE token = ?::uuid",
                Long.class, key);
        assertThat(resourceId).isEqualTo(idOf(result));
    }

    @Test
    @DisplayName("DS-11 同じ札・同じ中身でもう一度追加すると、201 で 1 回目と同じ id・同じ内容になり、タスクは 1 件のまま")
    void ds11SameKeySameBodyReturnsSameTask() {
        String key = newKey();
        // 前後の空白・優先度・期限・列まで指定して、返事の内容も同じになることを確かめる
        String body = """
                {"text": "  牛乳を買う  ", "priority": "high", "dueDate": "2030-01-02", "columnId": %d}
                """.formatted(doing);

        MvcTestResult first = postTask(key, body);
        MvcTestResult second = postTask(key, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(second).bodyJson().extractingPath("$.text").isEqualTo("牛乳を買う");
        assertThat(second).bodyJson().extractingPath("$.priority").isEqualTo("high");
        assertThat(second).bodyJson().extractingPath("$.dueDate").isEqualTo("2030-01-02");
        assertThat(second).bodyJson().extractingPath("$.columnId").isEqualTo(doing.intValue());
        assertThat(second).bodyJson().extractingPath("$.sortOrder")
                .isEqualTo(JsonPath.read(new String(first.getResponse().getContentAsByteArray(),
                        StandardCharsets.UTF_8), "$.sortOrder"));
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-11 指紋は前後の空白を除いたもの。優先度・列を省略した送り方と、既定値を書いた送り方は同じ中身として扱われる")
    void ds11FingerprintNormalizesBlankAndDefaults() {
        String key = newKey();

        MvcTestResult first = postTask(key, """
                {"text": "牛乳を買う"}
                """);
        // 空白つきの文章、優先度 medium・一番左の列を明示。設計書 2 節の指紋の決まりでは同じ中身
        MvcTestResult second = postTask(key, """
                {"text": " 牛乳を買う ", "priority": "medium", "columnId": %d}
                """.formatted(todo));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-12 同じ札で、ちがう中身（文章・優先度・期限・列のどれか）を送ると 409 になり、タスクは増えない")
    void ds12SameKeyDifferentBodyIsConflict() {
        String key = newKey();
        MvcTestResult first = postTask(key, """
                {"text": "牛乳を買う", "priority": "high", "dueDate": "2030-01-02", "columnId": %d}
                """.formatted(todo));
        assertThat(first).hasStatus(HttpStatus.CREATED);

        List<String> differentBodies = List.of(
                """
                {"text": "パンを買う", "priority": "high", "dueDate": "2030-01-02", "columnId": %d}
                """.formatted(todo),
                """
                {"text": "牛乳を買う", "priority": "low", "dueDate": "2030-01-02", "columnId": %d}
                """.formatted(todo),
                """
                {"text": "牛乳を買う", "priority": "high", "dueDate": "2030-01-03", "columnId": %d}
                """.formatted(todo),
                """
                {"text": "牛乳を買う", "priority": "high", "dueDate": null, "columnId": %d}
                """.formatted(todo),
                """
                {"text": "牛乳を買う", "priority": "high", "dueDate": "2030-01-02", "columnId": %d}
                """.formatted(doing));
        for (String body : differentBodies) {
            assertThat(postTask(key, body)).hasStatus(HttpStatus.CONFLICT);
        }

        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-13 1 回目のタスクを消してから同じ札で送り直すと 404 になり、タスクは作られない")
    void ds13SameKeyAfterDeleteIsNotFound() {
        String key = newKey();
        String body = """
                {"text": "牛乳を買う"}
                """;
        MvcTestResult first = postTask(key, body);
        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.delete().uri("/api/tasks/{id}", idOf(first))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(postTask(key, body)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("DS-14 札なしで追加を 2 回送ると、2 回とも 201 で 2 件できる（今までどおり）")
    void ds14WithoutKeyCreatesTwice() {
        String body = """
                {"text": "牛乳を買う"}
                """;

        MvcTestResult first = postTask(null, body);
        MvcTestResult second = postTask(null, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(idOf(second)).isNotEqualTo(idOf(first));
        assertThat(taskRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("DS-15 札が UUID でない（空文字・長すぎる・文字がちがう）と 400 になり、タスクは作られない")
    void ds15InvalidKeyIsBadRequest() {
        String body = """
                {"text": "牛乳を買う"}
                """;
        List<String> invalidKeys = List.of(
                "",
                "a".repeat(300),
                "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz",
                "not-a-uuid");

        for (String key : invalidKeys) {
            assertThat(postTask(key, body)).hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("DS-17 並び順の数字が同じタスクは、毎回同じ並び（id の小さい順）で返る")
    void ds17SameSortOrderIsStableById() {
        // 準備：同じ列に、同じ並び順の数字 0 で 5 件。1 件目の行を更新して、DB の中の物理的な並びをずらしておく
        // （第 2 のキー id がないと、同じ数字の並びは DB の都合で決まってしまう）
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(saveTask("タスク" + i, todo, 0).getId());
        }
        jdbc.update("UPDATE tasks SET text = text || '!' WHERE id = ?", ids.get(0));

        // 実行と確かめる：何回取っても、その列のタスクは id の小さい順
        for (int i = 0; i < 5; i++) {
            MvcTestResult result = mvc.get().uri("/api/tasks").exchange();
            String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            List<Number> idsInColumn = JsonPath.read(body, "$[?(@.columnId == " + todo + ")].id");
            assertThat(idsInColumn).extracting(Number::longValue).containsExactlyElementsOf(ids);
        }
    }

    @Test
    @DisplayName("DS-18 24 時間より古い札の行は、追加のたびに消える。新しい札の行は残る")
    void ds18OldTokensAreCleanedOnCreate() {
        // 準備：25 時間前の札と、1 時間前の札を、表に直接入れる
        UUID oldToken = UUID.randomUUID();
        UUID recentToken = UUID.randomUUID();
        jdbc.update("INSERT INTO request_tokens (token, kind, resource_id, fingerprint, created_at) "
                + "VALUES (?, 'task', 1, ?, now() - interval '25 hours')", oldToken, DUMMY_FINGERPRINT);
        jdbc.update("INSERT INTO request_tokens (token, kind, resource_id, fingerprint, created_at) "
                + "VALUES (?, 'task', 2, ?, now() - interval '1 hour')", recentToken, DUMMY_FINGERPRINT);
        String newKey = newKey();

        MvcTestResult result = postTask(newKey, """
                {"text": "牛乳を買う"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(tokenRows(oldToken.toString())).isZero();
        assertThat(tokenRows(recentToken.toString())).isEqualTo(1);
        assertThat(tokenRows(newKey)).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-18 札なしの追加では、新しい札の行は消えない")
    void ds18CreateWithoutKeyKeepsRecentTokens() {
        UUID recentToken = UUID.randomUUID();
        jdbc.update("INSERT INTO request_tokens (token, kind, resource_id, fingerprint, created_at) "
                + "VALUES (?, 'task', 2, ?, now() - interval '1 hour')", recentToken, DUMMY_FINGERPRINT);

        assertThat(postTask(null, """
                {"text": "牛乳を買う"}
                """)).hasStatus(HttpStatus.CREATED);

        assertThat(tokenRows(recentToken.toString())).isEqualTo(1);
    }
}
