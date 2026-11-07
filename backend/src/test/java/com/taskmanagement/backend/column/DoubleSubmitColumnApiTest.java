package com.taskmanagement.backend.column;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 二重送信の設計書（docs/二重送信の設計書.md）5.1 節の、列の削除（DS-04・05）と、番号札つきの列の追加（DS-20〜23）のテスト
// 札は、ヘッダー Idempotency-Key（UUID）。札がないときは今までどおり。DB は Testcontainers の使い捨ての PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DoubleSubmitColumnApiTest {

    private static final String KEY_HEADER = "Idempotency-Key";

    // 掃除のテスト用に入れる、ダミーの指紋（SHA-256 の 16 進と同じ 64 文字）
    private static final String DUMMY_FINGERPRINT = "0".repeat(64);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private BoardColumnRepository columnRepository;

    @Autowired
    private TaskRepository taskRepository;

    // 番号札の表（request_tokens）に直接さわるための道具
    @Autowired
    private JdbcTemplate jdbc;

    private Long todo;
    private Long doing;
    private Long done;

    // テストごとに、3列だけ・タスクが 1 件もない状態にする
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        doing = columnRepository.save(new BoardColumn("進行中", 1.0, true, false)).getId();
        done = columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true)).getId();
    }

    // 札つきで POST /api/columns を呼ぶ。key が null なら札なし
    private MvcTestResult postColumn(String key, String name) {
        var request = mvc.post().uri("/api/columns").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s"}
                        """.formatted(name));
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

    private Long saveCustomColumn(String name) {
        return columnRepository.save(new BoardColumn(name, 3.0, false, false)).getId();
    }

    private int tokenRows(String token) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM request_tokens WHERE token = ?::uuid",
                Integer.class, token);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("DS-04 消せる列を削除すると 204 になり、もう一度削除しても 204。列が消える")
    void ds04DeleteCustomColumnTwice() {
        Long review = saveCustomColumn("確認待ち");

        assertThat(mvc.delete().uri("/api/columns/{id}", review)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.delete().uri("/api/columns/{id}", review)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(columnRepository.existsById(review)).isFalse();
        assertThat(columnRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("DS-04 一度もなかった番号の列を削除しても 204")
    void ds04DeleteNeverExistedColumn() {
        Long review = saveCustomColumn("確認待ち");
        columnRepository.deleteById(review);

        assertThat(mvc.delete().uri("/api/columns/{id}", review + 1000)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("DS-05 基本の列・タスクが入っている列を削除すると 409（今のまま）で、列もタスクも残る")
    void ds05DeleteFixedOrNonEmptyColumnIsConflict() {
        Long review = saveCustomColumn("確認待ち");
        taskRepository.save(new Task("タスク", review, TaskPriority.MEDIUM, null, 0.0));

        for (Long fixed : List.of(todo, doing, done)) {
            assertThat(mvc.delete().uri("/api/columns/{id}", fixed)).hasStatus(HttpStatus.CONFLICT);
        }
        assertThat(mvc.delete().uri("/api/columns/{id}", review)).hasStatus(HttpStatus.CONFLICT);

        assertThat(columnRepository.count()).isEqualTo(4);
        assertThat(taskRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-20 札つきで列を追加し、同じ札・同じ名前でもう一度送ると、2 回目は 201 で同じ列になり、列は 1 つ")
    void ds20SameKeySameNameReturnsSameColumn() {
        String key = newKey();

        MvcTestResult first = postColumn(key, "確認待ち");
        // 名前の前後の空白は指紋に入らないので、空白つきの名前も同じ中身として扱われる
        MvcTestResult second = postColumn(key, "  確認待ち ");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(second).bodyJson().extractingPath("$.name").isEqualTo("確認待ち");
        assertThat(columnRepository.count()).isEqualTo(4);
        assertThat(tokenRows(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("DS-21 同じ札で、ちがう名前の列を送ると 409 になり、列は増えない")
    void ds21SameKeyDifferentNameIsConflict() {
        String key = newKey();
        assertThat(postColumn(key, "確認待ち")).hasStatus(HttpStatus.CREATED);

        assertThat(postColumn(key, "レビュー中")).hasStatus(HttpStatus.CONFLICT);

        assertThat(columnRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("DS-22 1 回目の列を消してから同じ札で送り直すと 404 になり、列は作られない")
    void ds22SameKeyAfterDeleteIsNotFound() {
        String key = newKey();
        MvcTestResult first = postColumn(key, "確認待ち");
        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.delete().uri("/api/columns/{id}", idOf(first))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(postColumn(key, "確認待ち")).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(columnRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("DS-23 札なしで列の追加を 2 回送ると、2 つできる（今までどおり）")
    void ds23WithoutKeyCreatesTwice() {
        MvcTestResult first = postColumn(null, "確認待ち");
        MvcTestResult second = postColumn(null, "確認待ち");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(idOf(second)).isNotEqualTo(idOf(first));
        assertThat(columnRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("DS-15 列の追加でも、札が UUID でない（空文字・長すぎる・文字がちがう）と 400 になり、列は作られない")
    void ds15InvalidKeyIsBadRequestForColumn() {
        for (String key : List.of("", "a".repeat(300), "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", "not-a-uuid")) {
            assertThat(postColumn(key, "確認待ち")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(columnRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("DS-25 右端の数字が同じ列は、毎回同じ並び（id の小さい順）で返る")
    void ds25SameSortOrderIsStableById() {
        // 準備：右端の数字 3.0 が同じ列を 5 つ。1 つ目の行を更新して、DB の中の物理的な並びをずらしておく
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(saveCustomColumn("列" + i));
        }
        jdbc.update("UPDATE board_columns SET name = name || '!' WHERE id = ?", ids.get(0));

        for (int i = 0; i < 5; i++) {
            MvcTestResult result = mvc.get().uri("/api/columns").exchange();
            String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            List<Number> listed = JsonPath.read(body, "$[?(@.sortOrder == 3.0)].id");
            assertThat(listed).extracting(Number::longValue).containsExactlyElementsOf(ids);
        }
    }

    @Test
    @DisplayName("DS-18 列の追加でも、24 時間より古い札の行は消え、新しい札の行は残る")
    void ds18OldTokensAreCleanedOnColumnCreate() {
        UUID oldToken = UUID.randomUUID();
        UUID recentToken = UUID.randomUUID();
        jdbc.update("INSERT INTO request_tokens (token, kind, resource_id, fingerprint, created_at) "
                + "VALUES (?, 'column', 1, ?, now() - interval '25 hours')", oldToken, DUMMY_FINGERPRINT);
        jdbc.update("INSERT INTO request_tokens (token, kind, resource_id, fingerprint, created_at) "
                + "VALUES (?, 'column', 2, ?, now() - interval '1 hour')", recentToken, DUMMY_FINGERPRINT);
        String key = newKey();

        assertThat(postColumn(key, "確認待ち")).hasStatus(HttpStatus.CREATED);

        assertThat(tokenRows(oldToken.toString())).isZero();
        assertThat(tokenRows(recentToken.toString())).isEqualTo(1);
        assertThat(tokenRows(key)).isEqualTo(1);
    }
}
