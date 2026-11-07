package com.taskmanagement.backend.column;

// API の返事専用の型（DTO）。画面に見せる項目だけを持つ（エンティティ BoardColumn をそのまま返さない）
// fixed：基本の列（消せない）か。done：完了の列か
// version：版の番号（docs/同時編集の設計書.md の 3.3 節）。画面は、次に書き込むときに、この版を一緒に送る
public record BoardColumnResponse(
        Long id,
        String name,
        Double sortOrder,
        boolean fixed,
        boolean done,
        Long version) {

    // エンティティ BoardColumn から、返事用の BoardColumnResponse を作る
    public static BoardColumnResponse from(BoardColumn column) {
        return new BoardColumnResponse(
                column.getId(),
                column.getName(),
                column.getSortOrder(),
                column.isFixed(),
                column.isDone(),
                column.getVersion());
    }
}
