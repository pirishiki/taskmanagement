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

        // 同じ列にあるタスクの件数を並び順にすると、列の一番下に入る
        int nextOrder = taskRepository.findByStatusOrderBySortOrderAsc(status).size();

        Task task = new Task(request.text().trim(), status, priority, request.dueDate(), nextOrder);
        return taskRepository.save(task);
    }
}
