package com.taskmanagement.backend.concurrentedit;

import com.taskmanagement.backend.column.BoardColumn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節「ボードの番号」のテスト（CE-45〜49）
// ヘッダー Board-Id：返事には必ず付き、書き込みで送られた番号が今の番号とちがえば 409（BOARD_CHANGED）
class ConcurrentEditBoardIdTest extends ConcurrentEditTestBase {

    private static final String HEADER = "Board-Id";
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String IMPORT_JSON = """
            {"version": 2, "columns": [
              {"name": "I-todo", "fixed": true, "tasks": [{"text": "I-1", "priority": "high"}]},
              {"name": "I-doing", "fixed": true, "tasks": []},
              {"name": "I-done", "fixed": true, "done": true, "tasks": []}
            ]}
            """;

    private static String boardIdOf(MvcTestResult result) {
        return result.getResponse().getHeader(HEADER);
    }

    private String currentBoardId() {
        return jdbc.queryForObject("SELECT board_id::text FROM board_meta", String.class);
    }

    private List<String> columnNames() {
        return columnRepository.findAllByOrderBySortOrderAsc().stream().map(BoardColumn::getName).toList();
    }

    // Board-Id を付けた書き込み。boardId が null ならヘッダーなし。json が null なら本文なし
    private MvcTestResult send(String method, String uri, String json, String boardId) {
        var request = switch (method) {
            case "POST" -> mvc.post().uri(uri);
            case "PUT" -> mvc.put().uri(uri);
            case "PATCH" -> mvc.patch().uri(uri);
            default -> mvc.delete().uri(uri);
        };
        if (boardId != null) {
            request.header(HEADER, boardId);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    @Test
    @DisplayName("CE-45 /api/ のどの返事（GET・POST・入力ミスの 400 も）にも Board-Id（UUID）が付き、同じ値で、board_meta と同じ")
    void ce45BoardIdOnEveryResponse() {
        List<MvcTestResult> results = List.of(
                get("/api/columns"),
                get("/api/tasks"),
                post("/api/tasks", "{\"text\":\"A\",\"columnId\":" + todo + "}"),
                post("/api/tasks", "{\"text\":\"\"}"));

        assertThat(statusOf(results.get(3))).isEqualTo(400);
        String expected = currentBoardId();
        for (MvcTestResult result : results) {
            String boardId = boardIdOf(result);
            assertThat(boardId).isNotNull();
            assertThat(boardId).matches(UUID_FORMAT);
            assertThat(boardId).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("CE-46 正しい Board-Id を付けた書き込みは、Board-Id なしと同じ結果（POST・PUT・PATCH・DELETE、move・reorder・import）")
    void ce46CorrectBoardIdBehavesNormally() {
        String id = boardIdOf(get("/api/columns"));
        assertThat(id).isNotNull();

        MvcTestResult created = send("POST", "/api/tasks", "{\"text\":\"A\",\"columnId\":" + todo + "}", id);
        assertThat(statusOf(created)).isEqualTo(201);
        long t = idOf(created);
        long u = createTask("U", todo);
        assertThat(statusOf(send("PUT", "/api/tasks/" + t, taskJson("B", "high", null), id))).isEqualTo(200);
        assertThat(statusOf(send("PATCH", "/api/tasks/" + t, "{\"columnId\":" + doing + "}", id))).isEqualTo(200);
        assertThat(statusOf(send("PUT", "/api/tasks/" + t + "/move",
                "{\"columnId\":" + todo + ",\"prevId\":null}", id))).isEqualTo(200);
        // 設計書 4.4：reorder の返事は 200（204 から変わる）
        assertThat(statusOf(send("PUT", "/api/tasks/reorder", "{\"columnId\":" + todo + ",\"orderedIds\":"
                + array(List.of(u, t)) + "}", id))).isEqualTo(200);
        assertThat(statusOf(send("DELETE", "/api/tasks/" + t, null, id))).isEqualTo(204);

        MvcTestResult column = send("POST", "/api/columns", "{\"name\":\"確認待ち\"}", id);
        assertThat(statusOf(column)).isEqualTo(201);
        long x = idOf(column);
        assertThat(statusOf(send("PUT", "/api/columns/" + x, "{\"name\":\"Renamed\"}", id))).isEqualTo(200);
        assertThat(statusOf(send("DELETE", "/api/columns/" + x, null, id))).isEqualTo(204);

        assertThat(statusOf(send("POST", "/api/tasks/import", IMPORT_JSON, id))).isEqualTo(204);
    }

    @Test
    @DisplayName("CE-47 board_id が変わったあと、前の Board-Id の書き込みは全部 409（BOARD_CHANGED）で、何も書かれない")
    void ce47OldBoardIdIsRejected() {
        long t = createT();
        String old = boardIdOf(get("/api/columns"));
        assertThat(old).isNotNull();
        jdbc.update("UPDATE board_meta SET board_id = gen_random_uuid()");
        String tBefore = taskBody(t);
        List<String> columnsBefore = columnNames();

        assertThat(boardIdOf(get("/api/tasks"))).isNotEqualTo(old);
        List<MvcTestResult> results = List.of(
                send("POST", "/api/tasks", "{\"text\":\"新しい\",\"columnId\":" + todo + "}", old),
                send("PUT", "/api/tasks/" + t, taskJson("X", "high", null), old),
                send("DELETE", "/api/tasks/" + t, null, old),
                send("DELETE", "/api/tasks/999999", null, old),
                send("POST", "/api/tasks/import", IMPORT_JSON, old),
                send("POST", "/api/tasks", "{\"text\":\"\"}", old));

        for (MvcTestResult result : results) {
            assertBoardChanged(result);
        }
        assertThat(taskBody(t)).isEqualTo(tBefore);
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(columnNames()).isEqualTo(columnsBefore);
    }

    @Test
    @DisplayName("CE-48 Board-Id なしの書き込みはふだんどおり。古い Board-Id を付けた GET は 200（読み取りは確かめない）")
    void ce48MissingBoardIdIsAcceptedAndGetIsNotChecked() {
        String old = UUID.randomUUID().toString();
        jdbc.update("UPDATE board_meta SET board_id = gen_random_uuid()");

        assertThat(statusOf(send("POST", "/api/tasks", "{\"text\":\"A\",\"columnId\":" + todo + "}", null)))
                .isEqualTo(201);
        MvcTestResult read = mvc.get().uri("/api/tasks").header(HEADER, old).exchange();
        assertThat(statusOf(read)).isEqualTo(200);
    }

    @Test
    @DisplayName("CE-49 Board-Id が UUID の形でない（abc・空文字）書き込みは 400 で、何も書かれない")
    void ce49MalformedBoardIdIsBadRequest() {
        long t = createT();
        String tBefore = taskBody(t);

        for (String malformed : List.of("abc", "")) {
            List<MvcTestResult> results = List.of(
                    send("POST", "/api/tasks", "{\"text\":\"新しい\",\"columnId\":" + todo + "}", malformed),
                    send("PUT", "/api/tasks/" + t, taskJson("X", "high", null), malformed),
                    send("DELETE", "/api/tasks/" + t, null, malformed));
            for (MvcTestResult result : results) {
                assertThat(statusOf(result)).as("Board-Id=" + malformed + " " + bodyOf(result)).isEqualTo(400);
                assertThat((String) read(result, "$.detail")).isEqualTo(DETAIL_BOARD_ID_FORMAT);
            }
        }
        assertThat(taskBody(t)).isEqualTo(tBefore);
        assertThat(taskRepository.count()).isEqualTo(1);
    }
}
