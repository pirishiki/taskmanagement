package com.taskmanagement.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    // その列で並び順が一番大きい（一番下の）タスクを1件だけ取る。列が空なら空の Optional
    Optional<Task> findTopByStatusOrderBySortOrderDesc(String status);

    List<Task> findAllByOrderByStatusAscSortOrderAsc();

    List<Task> findByTextContainingIgnoreCaseOrderByStatusAscSortOrderAsc(String keyword);
}
