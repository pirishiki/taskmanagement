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
}
