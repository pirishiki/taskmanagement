package com.taskmanagement.backend.task;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
// クラスに付けると、すべての public メソッドに効く。読み取りだけのメソッドは readOnly = true にして、書き込まないことを伝える
@Service
@Transactional
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    // タスクを取ってくる。keyword・priorities・due は、どれも省略できる（null なら、その条件では絞らない）
    // 条件が2つ以上あるときは、全部を満たすタスクだけを返す（AND）
    @Transactional(readOnly = true)
    public List<Task> findTasks(String keyword, List<TaskPriority> priorities, DueFilter due) {
        Specification<Task> conditions = Specification.allOf(
                TaskSpecifications.textContains(keyword),
                TaskSpecifications.priorityIn(priorities),
                TaskSpecifications.dueMatches(due, LocalDate.now()));
        // 並び順は、列（status）ごとにまとめ、その中は sortOrder の小さい順
        return taskRepository.findAll(conditions, Sort.by("status", "sortOrder"));
    }

    @Transactional(readOnly = true)
    public Optional<Task> findTask(Long id) {
        return taskRepository.findById(id);
    }

    public Task createTask(TaskRequest request) {
        // 省略されたときの値：status は「やるべきこと」、priority は「中」
        TaskStatus status = request.status() != null ? request.status() : TaskStatus.TODO;
        TaskPriority priority = request.priority() != null ? request.priority() : TaskPriority.MEDIUM;

        Task task = new Task(request.text().trim(), status, priority, request.dueDate(), nextOrderIn(status));
        return taskRepository.save(task);
    }

    // PUT 用：タスクの中身を丸ごと書き換える。タスクがなければ空の Optional を返す
    public Optional<Task> updateTask(Long id, TaskRequest request) {
        return taskRepository.findById(id).map(task -> {
            // status と priority は省略されたら今の値のまま（null を入れない）
            TaskStatus status = request.status() != null ? request.status() : task.getStatus();
            TaskPriority priority = request.priority() != null ? request.priority() : task.getPriority();

            // 別の列に移るときは、移動先の列の一番下に置く
            if (!status.equals(task.getStatus())) {
                task.setSortOrder(nextOrderIn(status));
            }

            task.setText(request.text().trim());
            task.setStatus(status);
            task.setPriority(priority);
            task.setDueDate(request.dueDate());
            return taskRepository.save(task);
        });
    }

    // PATCH 用：送られてきた項目（null でないもの）だけを書き換える。タスクがなければ空の Optional を返す
    public Optional<Task> patchTask(Long id, TaskPatchRequest request) {
        return taskRepository.findById(id).map(task -> {
            // 別の列に移るときは、移動先の列の一番下に置く（完了ボタンなら「終わったこと」の一番下）
            if (request.status() != null && !request.status().equals(task.getStatus())) {
                task.setSortOrder(nextOrderIn(request.status()));
                task.setStatus(request.status());
            }
            if (request.text() != null) {
                task.setText(request.text().trim());
            }
            if (request.priority() != null) {
                task.setPriority(request.priority());
            }
            if (request.dueDate() != null) {
                task.setDueDate(request.dueDate());
            }
            return taskRepository.save(task);
        });
    }

    // タスクを削除する。あれば消して true、なければ何もせず false を返す
    public boolean deleteTask(Long id) {
        if (!taskRepository.existsById(id)) {
            return false;
        }
        taskRepository.deleteById(id);
        return true;
    }

    // ドラッグ＆ドロップ用：カード（id）を、移動先の列（status）の、prevId のカードのすぐ下に入れる
    // 番号を変えるのは、動かしたカードだけ。ほかのカードには触らないので、画面に見えていないカードの順番もずれない
    // 動かすカードや prevId のカードがなければ 404、prevId が移動先の列にないなどのおかしな頼みなら 400
    public Task move(Long id, MoveRequest request) {
        Task task = taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        TaskStatus status = request.status();

        Task prev = null; // null のままなら、列の一番上に入れる
        if (request.prevId() != null) {
            if (request.prevId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "動かすカード自身を、上のカード（prevId）にはできません");
            }
            prev = taskRepository.findById(request.prevId())
                    .orElseThrow(() -> new TaskNotFoundException(request.prevId()));
            if (prev.getStatus() != status) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上のカード（prevId）が、移動先の列にありません");
            }
        }

        // 番号のすき間がなくなっていたら、列を 0, 1, 2… と振り直してから、もう一度決める
        Optional<Double> sortOrder = orderBelow(prev, status, id);
        if (sortOrder.isEmpty()) {
            renumber(status);
            sortOrder = orderBelow(prev, status, id);
        }

        task.setStatus(status);
        task.setSortOrder(sortOrder.orElseThrow());
        return taskRepository.save(task);
    }

    // prev のすぐ下に入れるときの番号を決める（prev が null なら列の一番上）。すき間がなければ空の Optional
    // 動かしているカード自身（movingId）は、まだ元の位置にあるので、隣を探すときに数えない
    private Optional<Double> orderBelow(Task prev, TaskStatus status, Long movingId) {
        if (prev == null) {
            // 一番上のカードより 1 小さくする（列が空なら 0）
            return Optional.of(taskRepository.findFirstByStatusAndIdNotOrderBySortOrderAsc(status, movingId)
                    .map(first -> first.getSortOrder() - 1)
                    .orElse(0.0));
        }

        // DB で本当に prev のすぐ下にあるカード（画面に見えていなくても）を探す
        Optional<Task> next = taskRepository.findFirstByStatusAndSortOrderGreaterThanAndIdNotOrderBySortOrderAsc(
                status, prev.getSortOrder(), movingId);
        if (next.isEmpty()) {
            return Optional.of(prev.getSortOrder() + 1); // prev が一番下なら、その下に入れる
        }

        // prev とすぐ下のカードの真ん中。すき間が狭すぎると、真ん中がどちらかと同じ数になってしまう
        double middle = (prev.getSortOrder() + next.get().getSortOrder()) / 2;
        if (middle == prev.getSortOrder() || middle == next.get().getSortOrder()) {
            return Optional.empty();
        }
        return Optional.of(middle);
    }

    // 列の番号を、今の順番のまま上から 0, 1, 2… と振り直す（真ん中の番号を作るすき間を、また空けるため）
    private void renumber(TaskStatus status) {
        List<Task> column = taskRepository.findByStatusOrderBySortOrderAsc(status);
        for (int position = 0; position < column.size(); position++) {
            column.get(position).setSortOrder((double) position);
        }
        taskRepository.saveAll(column);
    }

    // 自動並び替え（優先度順・期限が近い順）用：1つの列（status）の中で、送られてきたカードの順番を並べ替える
    // 「席の入れ替え」で行う：送られてきたカードが今持っている番号（席）を小さい順に並べ、送られてきた順番に配り直す
    // 送られてこなかったカード（絞り込みで画面に見えていないカード）の番号には触らないので、その順番はずれない
    // 列を移すことはしない（それは move の仕事）。別の列のカードが混じっていたら 400
    public void reorder(ReorderRequest request) {
        // 取ってきたタスクを「ID → タスク」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, Task> tasksById = taskRepository.findAllById(request.orderedIds()).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));

        for (Task task : tasksById.values()) {
            if (task.getStatus() != request.status()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "id が " + task.getId() + " のタスクは、並べ替える列（status）にありません");
            }
        }

        // 送られてきたカードが今座っている席（番号）を、小さい順に並べる
        List<Double> seats = tasksById.values().stream()
                .map(Task::getSortOrder)
                .sorted()
                .toList();

        // 送られてきた順番に、上の席から座ってもらう
        // 送られてきた ID のタスクがもう消えていたら飛ばす。同じ ID が2回あったら、2回目は飛ばす（distinct）
        List<Task> ordered = request.orderedIds().stream()
                .distinct()
                .map(tasksById::get)
                .filter(Objects::nonNull)
                .toList();
        for (int seat = 0; seat < ordered.size(); seat++) {
            ordered.get(seat).setSortOrder(seats.get(seat));
        }

        taskRepository.saveAll(ordered);
    }

    // 同じ列で一番大きい並び順より1つ大きくすると、列の一番下に入る（列が空なら 0）
    // 件数ではなく最大値を使うのは、削除で並び順に隙間ができても、ほかのタスクと同じ番号にならないようにするため
    private double nextOrderIn(TaskStatus status) {
        return taskRepository.findTopByStatusOrderBySortOrderDesc(status)
                .map(last -> last.getSortOrder() + 1)
                .orElse(0.0);
    }
}
