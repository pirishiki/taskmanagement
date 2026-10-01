package com.taskmanagement.backend.column;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 列のルールをまとめる係（作る・名前を変える・完了の列を決める・並べ替える）
// 列を消す処理は、タスクの側を列の番号に直してから足す（中にタスクがあるかを確かめる必要があるため）
// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
@Service
@Transactional
public class BoardColumnService {

    private final BoardColumnRepository columnRepository;

    public BoardColumnService(BoardColumnRepository columnRepository) {
        this.columnRepository = columnRepository;
    }

    // 全部の列を、左から順に返す
    @Transactional(readOnly = true)
    public List<BoardColumn> findColumns() {
        return columnRepository.findAllByOrderBySortOrderAsc();
    }

    // 新しい列を、右端に足す。新しい列には「完了の列」の印をつけない
    public BoardColumn createColumn(String name) {
        double sortOrder = columnRepository.findTopByOrderBySortOrderDesc()
                .map(last -> last.getSortOrder() + 1)
                .orElse(0.0);
        return columnRepository.save(new BoardColumn(name.trim(), sortOrder, false));
    }

    // 列の名前を変える。列がなければ 404
    public BoardColumn renameColumn(Long id, String name) {
        BoardColumn column = findOrThrow(id);
        column.setName(name.trim());
        return columnRepository.save(column);
    }

    // この列を「完了の列」にする。今まで印がついていた列からは、印を外す（印はいつも1つ）
    // 印を外すだけの操作は用意しない。完了の列が1つもないと、完了ボタン（○）の移し先がなくなるため
    public BoardColumn markAsDone(Long id) {
        BoardColumn column = findOrThrow(id);
        if (column.isDone()) {
            return column; // もう完了の列なら、何もしない
        }

        // 先に古い印を外して、すぐ DB へ書き込む（saveAndFlush）
        // 新しい印を先に書くと、一瞬だけ印が2つになり、V3 で作った「印は1つまで」の決まりに止められるため
        columnRepository.findByDoneTrue().ifPresent(old -> {
            old.setDone(false);
            columnRepository.saveAndFlush(old);
        });

        column.setDone(true);
        return columnRepository.save(column);
    }

    // 列を並べ替える。orderedIds は、全部の列の ID を、左から並べたい順に並べたもの
    // 列は多くても数個なので、全部の列に 0, 1, 2… と番号を振り直す
    // 足りない列・知らない列・同じ列が2回ある、のどれかなら 400
    public List<BoardColumn> reorderColumns(List<Long> orderedIds) {
        // 取ってきた列を「ID → 列」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, BoardColumn> columnsById = columnRepository.findAll().stream()
                .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));

        boolean sameColumns = orderedIds.size() == columnsById.size()
                && new HashSet<>(orderedIds).equals(columnsById.keySet());
        if (!sameColumns) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "全部の列の ID を、1回ずつ並べて送ってください");
        }

        List<BoardColumn> ordered = orderedIds.stream().map(columnsById::get).toList();
        for (int position = 0; position < ordered.size(); position++) {
            ordered.get(position).setSortOrder((double) position);
        }
        return columnRepository.saveAll(ordered);
    }

    // 列を取ってくる。なければ 404
    private BoardColumn findOrThrow(Long id) {
        return columnRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "id が " + id + " の列は見つかりません"));
    }
}
