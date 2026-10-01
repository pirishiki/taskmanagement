package com.taskmanagement.backend.column;

import com.taskmanagement.backend.task.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 列のルールをまとめる係（作る・名前を変える・並べ替える・消す）
// 完了の列は「終わったこと」に決まっていて、付け替えはしない
// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
@Service
@Transactional
public class BoardColumnService {

    private final BoardColumnRepository columnRepository;

    // 列を消す前に、中にタスクがいるかを確かめるために使う
    private final TaskRepository taskRepository;

    public BoardColumnService(BoardColumnRepository columnRepository, TaskRepository taskRepository) {
        this.columnRepository = columnRepository;
        this.taskRepository = taskRepository;
    }

    // 全部の列を、左から順に返す
    @Transactional(readOnly = true)
    public List<BoardColumn> findColumns() {
        return columnRepository.findAllByOrderBySortOrderAsc();
    }

    // 新しい列を、右端に足す。新しい列には「基本の列」「完了の列」の印をつけない（あとから消せる列になる）
    public BoardColumn createColumn(String name) {
        double sortOrder = columnRepository.findTopByOrderBySortOrderDesc()
                .map(last -> last.getSortOrder() + 1)
                .orElse(0.0);
        return columnRepository.save(new BoardColumn(name.trim(), sortOrder, false, false));
    }

    // 列の名前を変える。列がなければ 404
    public BoardColumn renameColumn(Long id, String name) {
        BoardColumn column = findOrThrow(id);
        column.setName(name.trim());
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

    // 列を消す。列がなければ 404
    // 次の列は消せない（409：今のボードの状態と合わないので、できない）
    // ・基本の列（やるべきこと・進行中・終わったこと）：カンバンの基本の流れなので残す。完了の列（終わったこと）もこれに入る
    // ・中にタスクがいる列：うっかりタスクを失わないように。先にタスクをほかの列へ移すか、消してもらう
    // （基本の列は消せないので、列が1つもなくなることも、完了ボタン（○）の移し先がなくなることもない）
    public void deleteColumn(Long id) {
        BoardColumn column = findOrThrow(id);
        if (column.isFixed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "基本の列（やるべきこと・進行中・終わったこと）は消せません");
        }
        if (taskRepository.existsByColumnId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "タスクが入っている列は消せません。先にタスクをほかの列へ移すか、消してください");
        }
        columnRepository.delete(column);
    }

    // 列を取ってくる。なければ 404
    private BoardColumn findOrThrow(Long id) {
        return columnRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "id が " + id + " の列は見つかりません"));
    }
}
