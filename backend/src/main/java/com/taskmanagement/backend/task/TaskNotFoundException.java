package com.taskmanagement.backend.task;

// 「その id のタスクが見つからない」ことを表す例外
// 投げると、GlobalExceptionHandler が受け止めて、404 の返事（ProblemDetail）にする
public class TaskNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // 見つからなかったタスクの id（返事の文に使う）
    private final Long taskId;

    public TaskNotFoundException(Long taskId) {
        super("id が " + taskId + " のタスクは見つかりません");
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
