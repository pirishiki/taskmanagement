package com.taskmanagement.backend.concurrentedit;

import com.jayway.jsonpath.JsonPath;
import com.taskmanagement.backend.TestcontainersConfiguration;
import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
import com.taskmanagement.backend.task.Task;
import com.taskmanagement.backend.task.TaskPriority;
import com.taskmanagement.backend.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節のテスト（CE-01〜49）に共通の準備と道具
// アプリを丸ごと起動し、DB は Testcontainers の使い捨ての PostgreSQL（既存の DoubleSubmit*Test と同じ作り）
// 期待値は設計書だけから決めている。version・versions・Board-Id は JSON・ヘッダーの文字で扱い、
// まだ実装のない Java の型や項目には触らない（実装前でもコンパイルでき、期待どおりの理由で落ちるようにするため）
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class ConcurrentEditTestBase {

    // 設計書 4.2 節の、409 の detail
    static final String DETAIL_TASK = "ほかの画面で、このタスクが変更されています。最新の内容を確かめてから、もう一度操作してください";
    static final String DETAIL_REORDER =
            "ほかの画面で、この列のタスクが変更されています。最新の状態を読み込んでから、もう一度並び替えてください";
    static final String DETAIL_COLUMN = "ほかの画面で、この列が変更されています。最新の内容を確かめてから、もう一度操作してください";
    static final String DETAIL_COLUMN_ORDER = "ほかの画面で、列が変更されています。最新の状態を読み込んでから、もう一度並べ替えてください";
    static final String DETAIL_SAFETY_NET = "ほかの画面で変更されています。最新の状態を読み込んでから、もう一度操作してください";
    // 設計書 4.1 節と 5 節の、400・409 の detail
    static final String DETAIL_VERSIONS_COUNT = "並べる順番（orderedIds）と版（versions）の数をそろえてください";
    static final String DETAIL_BOARD_CHANGED = "データベースが作り直されています。画面を読み込み直してください";
    static final String DETAIL_BOARD_ID_FORMAT = "ボードの番号（Board-Id）は UUID の形で送ってください";

    static final String VERSION_CONFLICT = "VERSION_CONFLICT";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected TaskRepository taskRepository;

    @Autowired
    protected BoardColumnRepository columnRepository;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Long todo;
    protected Long doing;
    protected Long done;

    // テストごとに、基本の 3 列だけ・タスクが 1 件もない状態にする（既存のテストと同じ）
    @BeforeEach
    void resetBoard() {
        taskRepository.deleteAll();
        columnRepository.deleteAll();
        todo = columnRepository.save(new BoardColumn("やるべきこと", 0.0, true, false)).getId();
        doing = columnRepository.save(new BoardColumn("進行中", 1.0, true, false)).getId();
        done = columnRepository.save(new BoardColumn("終わったこと", 2.0, true, true)).getId();
    }

    // ---- API を呼ぶ道具 ----

    protected MvcTestResult get(String uri) {
        return mvc.get().uri(uri).exchange();
    }

    protected MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    protected MvcTestResult put(String uri, String json) {
        return mvc.put().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    protected MvcTestResult patch(String uri, String json) {
        return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    protected MvcTestResult delete(String uri) {
        return mvc.delete().uri(uri).exchange();
    }

    // ---- 返事を読む道具 ----

    protected static String bodyOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    protected static int statusOf(MvcTestResult result) {
        return result.getResponse().getStatus();
    }

    protected static <T> T read(MvcTestResult result, String path) {
        return JsonPath.read(bodyOf(result), path);
    }

    protected static long idOf(MvcTestResult result) {
        return ((Number) read(result, "$.id")).longValue();
    }

    // 返事の JSON の一番上に、その名前の項目があるか（current がないことを確かめるのに使う）
    protected static boolean hasField(MvcTestResult result, String name) {
        Object parsed = JsonPath.read(bodyOf(result), "$");
        return parsed instanceof Map<?, ?> map && map.containsKey(name);
    }

    // ---- 準備の道具 ----

    // 札なしの POST /api/tasks でタスクを作り、その id を返す
    protected long createTask(String text, Long columnId) {
        MvcTestResult result = post("/api/tasks", "{\"text\":\"" + text + "\",\"columnId\":" + columnId + "}");
        assertThat(statusOf(result)).isEqualTo(201);
        return idOf(result);
    }

    // 設計書 7.1 節の「T」：todo に作った、version 0 のタスク
    protected long createT() {
        return createTask("A", todo);
    }

    protected Task saveTask(String text, Long columnId, double sortOrder) {
        return taskRepository.save(new Task(text, columnId, TaskPriority.MEDIUM, null, sortOrder));
    }

    protected long createColumn(String name) {
        MvcTestResult result = post("/api/columns", "{\"name\":\"" + name + "\"}");
        assertThat(statusOf(result)).isEqualTo(201);
        return idOf(result);
    }

    // PUT /api/tasks/{id} の本文。version が null なら書かない（版なし）
    protected static String taskJson(String text, String priority, Integer version) {
        String versionPart = version == null ? "" : ",\"version\":" + version;
        return "{\"text\":\"" + text + "\",\"priority\":\"" + priority + "\",\"dueDate\":null" + versionPart + "}";
    }

    // 設計書 7.1 節「版のずれ」の準備：T を PUT で version 1・text B にする
    protected long createTAtVersion1() {
        long t = createT();
        assertThat(statusOf(put("/api/tasks/" + t, taskJson("B", "medium", 0)))).isEqualTo(200);
        return t;
    }

    // ---- 今の状態を読む道具（API の GET から） ----

    protected String taskBody(long id) {
        return bodyOf(get("/api/tasks/" + id));
    }

    protected long taskVersion(long id) {
        return ((Number) JsonPath.read(taskBody(id), "$.version")).longValue();
    }

    protected String taskText(long id) {
        return JsonPath.read(taskBody(id), "$.text");
    }

    protected long taskColumn(long id) {
        return ((Number) JsonPath.read(taskBody(id), "$.columnId")).longValue();
    }

    protected double taskSortOrder(long id) {
        return ((Number) JsonPath.read(taskBody(id), "$.sortOrder")).doubleValue();
    }

    protected long columnVersion(long id) {
        List<Number> versions = JsonPath.read(bodyOf(get("/api/columns")), "$[?(@.id == " + id + ")].version");
        assertThat(versions).hasSize(1);
        return versions.get(0).longValue();
    }

    protected String columnName(long id) {
        List<String> names = JsonPath.read(bodyOf(get("/api/columns")), "$[?(@.id == " + id + ")].name");
        assertThat(names).hasSize(1);
        return names.get(0);
    }

    // GET /api/tasks の返事から、その列のタスクの id を、返ってきた順に取り出す
    protected List<Long> taskIdsInOrder(Long columnId) {
        List<Number> ids = JsonPath.read(bodyOf(get("/api/tasks")), "$[?(@.columnId == " + columnId + ")].id");
        return ids.stream().map(Number::longValue).toList();
    }

    // 今の列の id を、左から順に
    protected List<Long> columnIdsInOrder() {
        List<Number> ids = JsonPath.read(bodyOf(get("/api/columns")), "$[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    // 「[1,2,3]」の形の文字にする（ids や versions を JSON に埋め込む）
    protected static String array(List<? extends Number> numbers) {
        return numbers.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    // 列の id の並びに対応する、今の版の配列（PUT /api/columns/order の versions 用）
    protected String currentColumnVersions(List<Long> ids) {
        return array(ids.stream().map(this::columnVersion).toList());
    }

    // ---- 409（VERSION_CONFLICT）の確かめ ----

    protected static void assertVersionConflict(MvcTestResult result, String detail, boolean withCurrent) {
        assertThat(statusOf(result)).as(bodyOf(result)).isEqualTo(409);
        assertThat((String) read(result, "$.code")).isEqualTo(VERSION_CONFLICT);
        assertThat((String) read(result, "$.detail")).isEqualTo(detail);
        assertThat(hasField(result, "current")).isEqualTo(withCurrent);
    }

    protected static void assertBoardChanged(MvcTestResult result) {
        assertThat(statusOf(result)).as(bodyOf(result)).isEqualTo(409);
        assertThat((String) read(result, "$.code")).isEqualTo("BOARD_CHANGED");
        assertThat((String) read(result, "$.detail")).isEqualTo(DETAIL_BOARD_CHANGED);
    }
}
