package com.taskmanagement.backend.column;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 列の API の窓口。ルールの確かめと DB の操作は BoardColumnService に任せる
// 返事はすべて BoardColumnResponse（返事専用の型）にして返す
// 列が見つからないときの 404、おかしな頼みのときの 400 は、Service が投げた例外から GlobalExceptionHandler が作る
@RestController
@RequestMapping("/api/columns")
public class BoardColumnController {

    private final BoardColumnService columnService;

    public BoardColumnController(BoardColumnService columnService) {
        this.columnService = columnService;
    }

    // 例：GET /api/columns → 全部の列を、左から順に返す
    @GetMapping
    public List<BoardColumnResponse> getColumns() {
        return columnService.findColumns().stream()
                .map(BoardColumnResponse::from)
                .toList();
    }

    // 例：POST /api/columns  { "name": "確認待ち" } → 右端に列を足して、201 で返す
    @PostMapping
    public ResponseEntity<BoardColumnResponse> createColumn(@Valid @RequestBody BoardColumnRequest request) {
        BoardColumn created = columnService.createColumn(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(BoardColumnResponse.from(created));
    }

    // 例：PUT /api/columns/order  { "orderedIds": [3, 1, 2] } → 列を、この順に左から並べ直す
    // URL の "order" は、下の "/{id}" より先に選ばれる（Spring は、決まった文字の URL を優先する）
    @PutMapping("/order")
    public List<BoardColumnResponse> reorderColumns(@Valid @RequestBody BoardColumnOrderRequest request) {
        return columnService.reorderColumns(request.orderedIds()).stream()
                .map(BoardColumnResponse::from)
                .toList();
    }

    // 例：PUT /api/columns/2  { "name": "作業中" } → 列2の名前を変える
    @PutMapping("/{id}")
    public BoardColumnResponse renameColumn(@PathVariable Long id, @Valid @RequestBody BoardColumnRequest request) {
        return BoardColumnResponse.from(columnService.renameColumn(id, request.name()));
    }

    // 例：PUT /api/columns/2/done → 列2を「完了の列」にする（今までの完了の列からは印が外れる）。送る中身はない
    @PutMapping("/{id}/done")
    public BoardColumnResponse markAsDone(@PathVariable Long id) {
        return BoardColumnResponse.from(columnService.markAsDone(id));
    }

    // 例：DELETE /api/columns/4 → 列4を消す。成功したら 204（中身なし）
    // 中にタスクがいる列・完了の列は消せない（409）
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteColumn(@PathVariable Long id) {
        columnService.deleteColumn(id);
        return ResponseEntity.noContent().build();
    }
}
