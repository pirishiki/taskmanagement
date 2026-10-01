package com.taskmanagement.backend.task;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    // Controller は Service だけを使う（DB の操作は Service → Repository に任せる）
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    // 返事はすべて TaskResponse（返事専用の型）にして返す。エンティティ Task はそのまま返さない
    // タスクが見つからないときは TaskNotFoundException を投げる。404 の返事は GlobalExceptionHandler が作る

    // 例：GET /api/tasks?keyword=買い&priority=high&priority=medium&due=overdue
    // どれも省略できる。priority は何度でも書ける（書いた優先度のどれか）
    // priority・due の小文字を enum に変えるのは QueryParamConverters。決まっていない値なら 400 を返す
    @GetMapping
    public List<TaskResponse> getAllTasks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<TaskPriority> priority,
            @RequestParam(required = false) DueFilter due) {
        return taskService.findTasks(keyword, priority, due).stream()
                .map(TaskResponse::from)
                .toList();
    }

    // 書き出し：全部のタスクを、ファイルの形（TaskFile の JSON）で返す
    // Content-Disposition の札に attachment（添付ファイル）とファイル名を書くと、ブラウザは画面に出さずにファイルとして保存する
    // URL の "export" は、下の "/{id}" より先に選ばれる（Spring は、決まった文字の URL を優先する）
    @GetMapping("/export")
    public ResponseEntity<TaskFile> exportTasks() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("tasks-" + LocalDate.now() + ".json")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(taskService.exportTasks());
    }

    // 読み込み：今のタスクを全部消して、送られてきたファイルのタスクに置き換える。成功したら 204（中身なし）
    // @Valid で、ファイル全体と、中のタスク1件1件をチェックする。1件でもおかしければ 400 で、今のタスクは消えない
    @PostMapping("/import")
    public ResponseEntity<Void> importTasks(@Valid @RequestBody TaskFile file) {
        taskService.importTasks(file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public TaskResponse getTask(@PathVariable Long id) {
        return taskService.findTask(id)
                .map(TaskResponse::from)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody TaskRequest request) {
        Task created = taskService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(created));
    }

    @PutMapping("/{id}")
    public TaskResponse updateTask(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        return taskService.updateTask(id, request)
                .map(TaskResponse::from)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    @PatchMapping("/{id}")
    public TaskResponse patchTask(@PathVariable Long id, @Valid @RequestBody TaskPatchRequest request) {
        return taskService.patchTask(id, request)
                .map(TaskResponse::from)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        if (!taskService.deleteTask(id)) {
            throw new TaskNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }

    // 例：PUT /api/tasks/5/move  { "columnId": 2, "prevId": 3 } → タスク5を列2の、タスク3のすぐ下に入れる
    // 返事は、動かしたあとのタスク（新しい列と番号が入っている）
    @PutMapping("/{id}/move")
    public TaskResponse moveTask(@PathVariable Long id, @Valid @RequestBody MoveRequest request) {
        return TaskResponse.from(taskService.move(id, request));
    }

    // 自動並び替え用：1つの列の中で、送られてきたカードどうしの席（番号）を入れ替える
    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@Valid @RequestBody ReorderRequest request) {
        taskService.reorder(request);
        return ResponseEntity.noContent().build();
    }
}
