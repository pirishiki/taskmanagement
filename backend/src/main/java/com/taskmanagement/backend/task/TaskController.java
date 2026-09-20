package com.taskmanagement.backend.task;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class TaskController {

    private final TaskRepository taskRepository;

    public TaskController(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @GetMapping
    public List<Task> getAllTasks() {
        return taskRepository.findAllByOrderByStatusAscSortOrderAsc();
    }

    @PostMapping
    public Task createTask(@RequestBody TaskRequest request) {
        int nextOrder = taskRepository.findByStatusOrderBySortOrderAsc(request.status()).size();
        Task task = new Task(request.text(), request.status(), request.priority(), request.dueDate(), nextOrder);
        return taskRepository.save(task);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> updateTask(@PathVariable Long id, @RequestBody TaskRequest request) {
        return taskRepository.findById(id)
                .map(task -> {
                    task.setText(request.text());
                    task.setPriority(request.priority());
                    task.setDueDate(request.dueDate());
                    task.setStatus(request.status());
                    return ResponseEntity.ok(taskRepository.save(task));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        if (!taskRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        taskRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody ReorderRequest request) {
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
        return ResponseEntity.noContent().build();
    }
}
