package com.taskmanagement.backend.task;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TaskService {

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public List<Task> findTasks(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return taskRepository.findAllByOrderByStatusAscSortOrderAsc();
        }
        return taskRepository.findByTextContainingIgnoreCaseOrderByStatusAscSortOrderAsc(keyword);
    }

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

    // ドラッグ＆ドロップ用：1つの列（status）の並び順を、送られてきた ID の順番どおりにまとめて書き換える
    // 別の列から移ってきたタスクも、ここで status が書き換わる
    public void reorder(ReorderRequest request) {
        List<Task> tasks = taskRepository.findAllById(request.orderedIds());

        for (int position = 0; position < request.orderedIds().size(); position++) {
            Long taskId = request.orderedIds().get(position);
            int order = position;
            tasks.stream()
                    .filter(task -> task.getId().equals(taskId))
                    .findFirst()
                    .ifPresent(task -> {
                        task.setStatus(request.status());
                        task.setSortOrder(order);
                    });
        }

        taskRepository.saveAll(tasks);
    }

    // 同じ列で一番大きい並び順より1つ大きくすると、列の一番下に入る（列が空なら 0）
    // 件数ではなく最大値を使うのは、削除で並び順に隙間ができても、ほかのタスクと同じ番号にならないようにするため
    private int nextOrderIn(String status) {
        List<Task> tasks = taskRepository.findByStatusOrderBySortOrderAsc(status);
        if (tasks.isEmpty()) {
            return 0;
        }
        return tasks.get(tasks.size() - 1).getSortOrder() + 1;
    }
}
