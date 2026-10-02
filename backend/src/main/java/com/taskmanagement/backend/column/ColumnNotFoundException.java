package com.taskmanagement.backend.column;

// 「その id の列が見つからない」ことを表す例外（タスクの TaskNotFoundException と同じ形）
// 投げると、GlobalExceptionHandler が受け止めて、404 の返事（ProblemDetail）にする
public class ColumnNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // 見つからなかった列の id（返事の文に使う）
    private final Long columnId;

    public ColumnNotFoundException(Long columnId) {
        super("id が " + columnId + " の列は見つかりません");
        this.columnId = columnId;
    }

    public Long getColumnId() {
        return columnId;
    }
}
