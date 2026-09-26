package com.taskmanagement.backend.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public List<Task> findTasks(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return taskRepository.findAllByOrderByStatusAscSortOrderAsc();
        }
        return taskRepository.findByTextContainingIgnoreCaseOrderByStatusAscSortOrderAsc(keyword);
    }

    @Transactional(readOnly = true)
    public Optional<Task> findTask(Long id) {
        return taskRepository.findById(id);
    }

    public Task createTask(TaskRequest request) {
        String status = request.status() != null ? request.status() : "todo";
        String priority = request.priority() != null ? request.priority() : "medium";

        Task task = new Task(request.text().trim(), status, priority, request.dueDate(), nextOrderIn(status));
        return taskRepository.save(task);
    }

    // PUT 用：タスクの中身を丸ごと書き換える。タスクがなければ空の Optional を返す
    public Optional<Task> updateTask(Long id, TaskRequest request) {
        return taskRepository.findById(id).map(task -> {
            // status と priority は省略されたら今の値のまま（null を入れない）
            String status = request.status() != null ? request.status() : task.getStatus();
            String priority = request.priority() != null ? request.priority() : task.getPriority();

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

    // ドラッグ＆ドロップ用：1つの列（status）の並び順を、送られてきた ID の順番どおりにまとめて書き換える
    // 別の列から移ってきたタスクも、ここで status が書き換わる
    public void reorder(ReorderRequest request) {
        // 取ってきたタスクを「ID → タスク」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, Task> tasksById = taskRepository.findAllById(request.orderedIds()).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));

        for (int position = 0; position < request.orderedIds().size(); position++) {
            Task task = tasksById.get(request.orderedIds().get(position));
            if (task != null) { // 送られてきた ID のタスクがもう消えていたら、飛ばす
                task.setStatus(request.status());
                task.setSortOrder(position);
            }
        }

        taskRepository.saveAll(tasksById.values());
    }

    // 同じ列で一番大きい並び順より1つ大きくすると、列の一番下に入る（列が空なら 0）
    // 件数ではなく最大値を使うのは、削除で並び順に隙間ができても、ほかのタスクと同じ番号にならないようにするため
    private int nextOrderIn(String status) {
        return taskRepository.findTopByStatusOrderBySortOrderDesc(status)
                .map(last -> last.getSortOrder() + 1)
                .orElse(0);
    }
}
