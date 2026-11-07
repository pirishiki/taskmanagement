package com.taskmanagement.backend.column;

import com.taskmanagement.backend.idempotency.RequestTokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    // ヘッダー Idempotency-Key（番号札、UUID）は省略できる。付けると、同じ札で送り直しても列は 1 つだけ（2 回目は同じ列を返す）
    // 札が UUID の形でなければ 400（RequestTokens.parseKey）
    @PostMapping
    public ResponseEntity<BoardColumnResponse> createColumn(
            @RequestHeader(name = RequestTokens.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody BoardColumnRequest request) {
        BoardColumn created = columnService.createColumn(request.name(), RequestTokens.parseKey(idempotencyKey));
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

    // 例：DELETE /api/columns/4 → 列4を消す。成功したら 204（中身なし）。もうない列でも 204（2 回目の削除も成功）
    // 基本の列（やるべきこと・進行中・終わったこと）・中にタスクがいる列は消せない（409）
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteColumn(@PathVariable Long id) {
        columnService.deleteColumn(id);
        return ResponseEntity.noContent().build();
    }
}
